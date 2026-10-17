package soar

import (
	"math"
)

// ShannonEntropy calculates the Shannon entropy of a byte slice in bits per symbol.
// Standard unencrypted English text/names: ~2.5 - 3.5 bits.
// Uniform random / encrypted Base32/Hex: ~4.7 - 5.0 bits.
func ShannonEntropy(data []byte) float64 {
	if len(data) == 0 {
		return 0.0
	}

	var freq [256]int
	for _, b := range data {
		freq[b]++
	}

	total := float64(len(data))
	entropy := 0.0
	for _, count := range freq {
		if count > 0 {
			p := float64(count) / total
			entropy -= p * math.Log2(p)
		}
	}
	return entropy
}

// Common natural semantic prefixes to intersperse into query labels
var naturalPrefixes = []string{
	"cdn", "api", "edge", "sync", "node", "data", "cloud", "gw", "v2", "telemetry",
}

// MaskLabel formats an encoded Base32 string with structured hyphenated segments,
// reducing continuous character monotony and lowering heuristic DPI detection flags.
func MaskLabel(src string) string {
	if len(src) <= 8 {
		return src
	}
	// Insert hyphen breaks every 8-12 characters
	var res []byte
	chunkSize := 10
	for i := 0; i < len(src); i += chunkSize {
		end := min(i+chunkSize, len(src))
		if i > 0 {
			res = append(res, '-')
		}
		res = append(res, src[i:end]...)
	}
	return string(res)
}
