package soar

import (
	"fmt"
	"math"
	"sync"
	"sync/atomic"
	"time"
)

// NetworkProfileMode represents the computed operational posture based on real-time network telemetry.
type NetworkProfileMode string

const (
	ModeUltraFast NetworkProfileMode = "ultra_fast" // <90ms RTT, <2% loss: aggressively low timeouts, high concurrency
	ModeBalanced  NetworkProfileMode = "balanced"   // 90-250ms RTT: standard mobile / broadband tuning
	ModeResilient NetworkProfileMode = "resilient"  // >250ms RTT or >8% loss: extended timeouts, increased FEC parity
	ModeCrisis    NetworkProfileMode = "crisis"     // >20% loss or heavy dampening: max FEC redundancy, conservative concurrency
)

// TunerSnapshot provides a thread-safe snapshot of tuning recommendations and telemetry.
type TunerSnapshot struct {
	Mode         NetworkProfileMode `json:"mode"`
	EWMARTT      time.Duration      `json:"ewma_rtt"`
	Jitter       time.Duration      `json:"jitter"`
	LossRate     float64            `json:"loss_rate"`
	DialTimeout  time.Duration      `json:"dial_timeout"`
	FECParity    int                `json:"fec_parity"`
	MaxWindow    int                `json:"max_window"`
	ConsecLosses int                `json:"consec_losses"`
	TotalSamples uint64             `json:"total_samples"`
}

// AdaptiveTuner continuously tracks latency samples, jitter, and packet loss
// to self-tune dial timeouts, FEC parity rates, and congestion window bounds.
type AdaptiveTuner struct {
	mu sync.RWMutex

	ewmaRTT  float64 // in milliseconds
	ewmaVar  float64 // variance / mean deviation in ms
	lossEWMA float64 // 0.0 to 1.0

	consecLosses int
	totalSamples atomic.Uint64
	lostSamples  atomic.Uint64

	cachedMode    NetworkProfileMode
	cachedTimeout time.Duration
	cachedFEC     int
	cachedMaxWin  int

	lastUpdate time.Time
}

// NewAdaptiveTuner initializes an adaptive tuner with sensible defaults.
func NewAdaptiveTuner() *AdaptiveTuner {
	t := &AdaptiveTuner{
		ewmaRTT:       120.0, // initial estimate: 120ms
		ewmaVar:       30.0,
		lossEWMA:      0.0,
		cachedMode:    ModeBalanced,
		cachedTimeout: 3500 * time.Millisecond,
		cachedFEC:     6,
		cachedMaxWin:  6,
		lastUpdate:    time.Now(),
	}
	t.recomputeLocked()
	return t
}

// RecordSample records a transmission outcome (RTT and whether it was lost/timed out).
func (t *AdaptiveTuner) RecordSample(rtt time.Duration, lost bool) {
	t.mu.Lock()
	defer t.mu.Unlock()

	t.totalSamples.Add(1)
	now := time.Now()
	t.lastUpdate = now

	if lost {
		t.lostSamples.Add(1)
		t.consecLosses++
		// Loss EWMA moves aggressively upwards on loss
		t.lossEWMA = 0.85*t.lossEWMA + 0.15*1.0
	} else {
		t.consecLosses = 0
		t.lossEWMA = 0.90 * t.lossEWMA // smoothly decays towards 0

		rttMs := float64(rtt.Milliseconds())
		if rttMs < 10 {
			rttMs = 10
		} else if rttMs > 8000 {
			rttMs = 8000
		}

		// Standard RFC 6298 Jacobson EWMA smoothing
		delta := math.Abs(rttMs - t.ewmaRTT)
		t.ewmaVar = 0.85*t.ewmaVar + 0.15*delta
		t.ewmaRTT = 0.85*t.ewmaRTT + 0.15*rttMs
	}

	t.recomputeLocked()
}

func (t *AdaptiveTuner) recomputeLocked() {
	// Mode Classification
	switch {
	case t.lossEWMA >= 0.18 || t.consecLosses >= 3:
		t.cachedMode = ModeCrisis
		t.cachedTimeout = 5500 * time.Millisecond
		t.cachedFEC = 3 // 1 parity per 3 data packets (33% redundancy)
		t.cachedMaxWin = 3
	case t.lossEWMA >= 0.07 || t.ewmaRTT > 280.0:
		t.cachedMode = ModeResilient
		calcTimeout := time.Duration(math.Max(2500, math.Min(6000, 3.5*t.ewmaRTT))) * time.Millisecond
		t.cachedTimeout = calcTimeout
		t.cachedFEC = 4 // 1 parity per 4 data packets (25% redundancy)
		t.cachedMaxWin = 5
	case t.ewmaRTT < 85.0 && t.lossEWMA < 0.02:
		t.cachedMode = ModeUltraFast
		calcTimeout := time.Duration(math.Max(1500, math.Min(3000, 2.5*t.ewmaRTT))) * time.Millisecond
		t.cachedTimeout = calcTimeout
		t.cachedFEC = 8 // 1 parity per 8 data packets (12.5% redundancy)
		t.cachedMaxWin = 10
	default:
		t.cachedMode = ModeBalanced
		calcTimeout := time.Duration(math.Max(2000, math.Min(4500, 3.0*t.ewmaRTT))) * time.Millisecond
		t.cachedTimeout = calcTimeout
		t.cachedFEC = 6 // 1 parity per 6 data packets (16.7% redundancy)
		t.cachedMaxWin = 7
	}
}

// DialTimeout returns the dynamically clamped dial timeout for domain dialing.
func (t *AdaptiveTuner) DialTimeout() time.Duration {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cachedTimeout
}

// FECParityRate returns the recommended FEC parity interval.
func (t *AdaptiveTuner) FECParityRate() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cachedFEC
}

// MaxWindow returns the recommended concurrency window bound.
func (t *AdaptiveTuner) MaxWindow() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cachedMaxWin
}

// Mode returns the active operational posture.
func (t *AdaptiveTuner) Mode() NetworkProfileMode {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cachedMode
}

// Snapshot returns a copy of the current tuning telemetry.
func (t *AdaptiveTuner) Snapshot() TunerSnapshot {
	t.mu.RLock()
	defer t.mu.RUnlock()

	return TunerSnapshot{
		Mode:         t.cachedMode,
		EWMARTT:      time.Duration(t.ewmaRTT) * time.Millisecond,
		Jitter:       time.Duration(t.ewmaVar) * time.Millisecond,
		LossRate:     t.lossEWMA,
		DialTimeout:  t.cachedTimeout,
		FECParity:    t.cachedFEC,
		MaxWindow:    t.cachedMaxWin,
		ConsecLosses: t.consecLosses,
		TotalSamples: t.totalSamples.Load(),
	}
}

// ComputeResolverScore computes a normalized fitness score (0.0 to 1.0+)
// based on latency, success count, and consecutive failure penalties.
func ComputeResolverScore(rttMs float64, successes, failures int) float64 {
	if rttMs <= 0 {
		rttMs = 500.0
	}
	s := float64(successes)
	f := float64(failures)

	reliability := (s + 1.0) / (s + 2.0*f + 1.0)
	latencyFactor := 1000.0 / (rttMs + 60.0)

	return reliability * latencyFactor
}

// TelemetryLine formats the tuner state into an easily parseable line for GUI clients.
func (t *AdaptiveTuner) TelemetryLine(bestResolvers, totalResolvers int) string {
	s := t.Snapshot()
	return fmt.Sprintf("[TUNER] mode=%s rtt_ewma=%.1fms jitter=%.1fms loss=%.1f%% dial_timeout=%.2fs fec=1:%d max_win=%d best=%d total=%d",
		s.Mode,
		float64(s.EWMARTT.Milliseconds()),
		float64(s.Jitter.Milliseconds()),
		s.LossRate*100.0,
		s.DialTimeout.Seconds(),
		s.FECParity,
		s.MaxWindow,
		bestResolvers,
		totalResolvers,
	)
}
