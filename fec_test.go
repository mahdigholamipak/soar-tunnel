package soar

import (
	"bytes"
	"testing"
)

func TestFEC_ComputeAndReconstruct(t *testing.T) {
	// 4 data packets
	d0 := []byte("hello soar dns tunnel")
	d1 := []byte("packet 2 with some data")
	d2 := []byte("packet 3 longer than previous packets 1234567890")
	d3 := []byte("short")

	packets := [][]byte{d0, d1, d2, d3}
	parity := ComputeParity(packets)

	if len(parity) != len(d2) {
		t.Fatalf("expected parity length %d, got %d", len(d2), len(parity))
	}

	// Test dropping each packet in turn and reconstructing it
	for dropIdx := 0; dropIdx < len(packets); dropIdx++ {
		var known [][]byte
		for i, p := range packets {
			if i != dropIdx {
				known = append(known, p)
			}
		}

		recovered := ReconstructMissing(parity, known)

		// The original packet was zero-padded during XOR, so compare up to original length
		orig := packets[dropIdx]
		if !bytes.Equal(recovered[:len(orig)], orig) {
			t.Fatalf("drop %d: recovered mismatch: expected %q, got %q", dropIdx, orig, recovered[:len(orig)])
		}
	}
}

func TestFEC_EncoderDecoder(t *testing.T) {
	encoder := NewFECEncoder(4)
	decoder := NewFECDecoder()

	p0 := []byte("chunk zero")
	p1 := []byte("chunk one")
	p2 := []byte("chunk two")
	p3 := []byte("chunk three")

	// Send 3 packets
	if f := encoder.AddPacket(10, p0); f != nil {
		t.Fatal("unexpected FEC frame before K=4")
	}
	if f := encoder.AddPacket(11, p1); f != nil {
		t.Fatal("unexpected FEC frame before K=4")
	}
	if f := encoder.AddPacket(12, p2); f != nil {
		t.Fatal("unexpected FEC frame before K=4")
	}

	// 4th packet completes group and produces parity
	f := encoder.AddPacket(13, p3)
	if f == nil {
		t.Fatal("expected FEC parity frame after 4 packets")
	}
	if len(f.PNs) != 4 || f.PNs[0] != 10 || f.PNs[3] != 13 {
		t.Fatalf("unexpected PNs in FEC frame: %v", f.PNs)
	}

	// Test serialization of FEC frame
	var buf []byte
	buf = appendFEC(buf, f)

	var parsed *fecFrame
	err := parseFrames(buf, func(fr *frame) error {
		if fr.typ == frFEC {
			parsed = fr.fec
		}
		return nil
	})
	if err != nil {
		t.Fatalf("parseFrames failed: %v", err)
	}
	if parsed == nil || parsed.GroupID != f.GroupID || !bytes.Equal(parsed.Parity, f.Parity) {
		t.Fatal("FEC frame serialization roundtrip mismatch")
	}

	// Now simulate receiver where packet 11 was DROPPED:
	decoder.RecordPacket(10, p0)
	// packet 11 lost!
	decoder.RecordPacket(12, p2)
	decoder.RecordPacket(13, p3)

	lostPN, recoveredPayload, ok := decoder.HandleFECFrame(parsed)
	if !ok {
		t.Fatal("expected decoder to recover missing packet")
	}
	if lostPN != 11 {
		t.Fatalf("expected recovered PN 11, got %d", lostPN)
	}
	if !bytes.Equal(recoveredPayload[:len(p1)], p1) {
		t.Fatalf("recovered payload mismatch: expected %q, got %q", p1, recoveredPayload[:len(p1)])
	}
}

func TestFEC_EncoderFlush(t *testing.T) {
	encoder := NewFECEncoder(4)
	encoder.AddPacket(1, []byte("data 1"))
	encoder.AddPacket(2, []byte("data 2"))

	f := encoder.Flush()
	if f == nil {
		t.Fatal("expected FEC frame upon flush of 2 packets")
	}
	if f.K != 2 || len(f.PNs) != 2 {
		t.Fatalf("expected K=2 in flushed frame, got %d", f.K)
	}
}
