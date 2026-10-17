package soar

import (
	"sync"
)

// FECGroupSize defines how many data packets form an FEC block before emitting a parity frame.
// A ratio of 4:1 (K=4, M=1) or 8:1 offers resilience against single-packet UDP dropouts
// without substantial bandwidth overhead.
const (
	DefaultFECGroupSize = 4
	MaxFECGroupSize     = 16
	frFEC               = 0x10
)

// fecFrame represents an encoded FEC parity frame.
type fecFrame struct {
	GroupID uint64
	Idx     byte
	K       byte
	PNs     []uint64
	Parity  []byte
}

// appendFEC encodes an FEC parity frame into b.
func appendFEC(b []byte, f *fecFrame) []byte {
	b = append(b, frFEC)
	b = appendVarint(b, f.GroupID)
	b = append(b, f.Idx, f.K)
	b = appendVarint(b, uint64(len(f.PNs)))
	for _, pn := range f.PNs {
		b = appendVarint(b, pn)
	}
	b = appendVarint(b, uint64(len(f.Parity)))
	return append(b, f.Parity...)
}

// xorBytes computes dst = dst ^ src in place up to min(len(dst), len(src)).
func xorBytes(dst, src []byte) {
	n := min(len(dst), len(src))
	for i := 0; i < n; i++ {
		dst[i] ^= src[i]
	}
}

// ComputeParity generates an XOR parity block across all provided packet payloads.
// Shorter packets are treated as zero-padded to the length of the longest packet.
func ComputeParity(packets [][]byte) []byte {
	if len(packets) == 0 {
		return nil
	}
	maxLen := 0
	for _, p := range packets {
		if len(p) > maxLen {
			maxLen = len(p)
		}
	}
	if maxLen == 0 {
		return nil
	}

	parity := make([]byte, maxLen)
	for _, p := range packets {
		xorBytes(parity, p)
	}
	return parity
}

// ReconstructMissing recreates one missing packet payload from the parity block and all other
// known packets in the group:
// Missing = Parity ^ (D_0 ^ D_1 ^ ... ^ D_{k-1} excluding missing)
func ReconstructMissing(parity []byte, knownPackets [][]byte) []byte {
	res := make([]byte, len(parity))
	copy(res, parity)
	for _, p := range knownPackets {
		xorBytes(res, p)
	}
	return res
}

// FECEncoder buffers sent packets and emits an FEC parity frame every K packets.
type FECEncoder struct {
	mu       sync.Mutex
	k        int
	groupID  uint64
	currPNs  []uint64
	currPkts [][]byte
	pending  []*fecFrame
}

// NewFECEncoder creates a new FEC encoder with group size k (default 4).
func NewFECEncoder(k int) *FECEncoder {
	if k <= 1 || k > MaxFECGroupSize {
		k = DefaultFECGroupSize
	}
	return &FECEncoder{
		k: k,
	}
}

// AddPacket records an outgoing data packet (by packet number and plaintext payload).
// If adding this packet completes a group of K, it generates a parity frame ready to send.
func (e *FECEncoder) AddPacket(pn uint64, payload []byte) *fecFrame {
	e.mu.Lock()
	defer e.mu.Unlock()

	cp := make([]byte, len(payload))
	copy(cp, payload)

	e.currPNs = append(e.currPNs, pn)
	e.currPkts = append(e.currPkts, cp)

	if len(e.currPkts) >= e.k {
		parity := ComputeParity(e.currPkts)
		f := &fecFrame{
			GroupID: e.groupID,
			Idx:     byte(e.k),
			K:       byte(e.k),
			PNs:     append([]uint64(nil), e.currPNs...),
			Parity:  parity,
		}
		e.groupID++
		e.currPNs = e.currPNs[:0]
		e.currPkts = e.currPkts[:0]
		return f
	}
	return nil
}

// Flush forces any partially-filled FEC group to produce a parity frame for remaining packets.
func (e *FECEncoder) Flush() *fecFrame {
	e.mu.Lock()
	defer e.mu.Unlock()

	if len(e.currPkts) <= 1 {
		return nil
	}
	parity := ComputeParity(e.currPkts)
	f := &fecFrame{
		GroupID: e.groupID,
		Idx:     byte(len(e.currPkts)),
		K:       byte(len(e.currPkts)),
		PNs:     append([]uint64(nil), e.currPNs...),
		Parity:  parity,
	}
	e.groupID++
	e.currPNs = e.currPNs[:0]
	e.currPkts = e.currPkts[:0]
	return f
}

// fecDecoderGroup tracks incoming packets for an active FEC block.
type fecDecoderGroup struct {
	k       int
	pns     []uint64
	packets map[uint64][]byte
	parity  []byte
}

// FECDecoder receives packets and parity frames, instantly reconstructing any single missing packet.
type FECDecoder struct {
	mu     sync.Mutex
	groups map[uint64]*fecDecoderGroup
	seenPN map[uint64]uint64 // pn -> groupID
}

// NewFECDecoder creates a new FEC decoder.
func NewFECDecoder() *FECDecoder {
	return &FECDecoder{
		groups: make(map[uint64]*fecDecoderGroup),
		seenPN: make(map[uint64]uint64),
	}
}

// RecordPacket records arrival of a normal data packet.
func (d *FECDecoder) RecordPacket(pn uint64, payload []byte) {
	d.mu.Lock()
	defer d.mu.Unlock()

	gid, ok := d.seenPN[pn]
	if !ok {
		return
	}
	g := d.groups[gid]
	if g == nil {
		return
	}
	if _, exists := g.packets[pn]; !exists {
		cp := make([]byte, len(payload))
		copy(cp, payload)
		g.packets[pn] = cp
		if len(g.packets) == g.k {
			delete(d.groups, gid)
		}
	}
}

// HandleFECFrame processes an incoming FEC parity frame.
// If exactly one packet is missing from the group, it reconstructs it and returns (lostPN, recoveredPayload, true).
func (d *FECDecoder) HandleFECFrame(f *fecFrame) (uint64, []byte, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()

	g, exists := d.groups[f.GroupID]
	if !exists {
		g = &fecDecoderGroup{
			k:       int(f.K),
			pns:     append([]uint64(nil), f.PNs...),
			packets: make(map[uint64][]byte),
			parity:  append([]byte(nil), f.Parity...),
		}
		d.groups[f.GroupID] = g
		for _, pn := range f.PNs {
			d.seenPN[pn] = f.GroupID
		}
	} else {
		g.parity = append([]byte(nil), f.Parity...)
	}

	// Clean up old groups to prevent unbounded memory growth (keep at most 32 active groups)
	if len(d.groups) > 32 {
		for gid := range d.groups {
			if gid+16 < f.GroupID {
				delete(d.groups, gid)
			}
		}
	}

	// Check how many packets are missing
	var missingPN uint64
	missingCount := 0
	var known [][]byte

	for _, pn := range g.pns {
		if pkt, ok := g.packets[pn]; ok {
			known = append(known, pkt)
		} else {
			missingCount++
			missingPN = pn
		}
	}

	if missingCount == 1 {
		recovered := ReconstructMissing(g.parity, known)
		g.packets[missingPN] = recovered
		delete(d.groups, f.GroupID)
		return missingPN, recovered, true
	} else if missingCount == 0 {
		delete(d.groups, f.GroupID)
	}

	return 0, nil, false
}
