package soar

import (
	"bytes"
	"testing"
)

func TestEntropy_ShannonCalculation(t *testing.T) {
	// Monotonous / uniform byte repeats have low entropy
	zeros := make([]byte, 100)
	if e := ShannonEntropy(zeros); e != 0.0 {
		t.Fatalf("expected 0 entropy for repeated byte, got %f", e)
	}

	// Standard English ASCII text has moderate entropy (typically 2.5 - 3.8)
	english := []byte("The quick brown fox jumps over the lazy dog and runs through the forest")
	eEnglish := ShannonEntropy(english)
	if eEnglish < 2.0 || eEnglish > 4.5 {
		t.Fatalf("expected English text entropy between 2.0 and 4.5, got %f", eEnglish)
	}

	// Base32 encoded random string has high entropy (~4.5 - 5.0)
	b32Data := []byte("abcdefghijklmnopqrstuvwxyz234567")
	eB32 := ShannonEntropy(b32Data)
	if eB32 < 4.5 {
		t.Fatalf("expected high entropy for base32 alphabet, got %f", eB32)
	}
}

func TestEntropy_MaskLabel(t *testing.T) {
	raw := "abcdefghijklmnopqrstuvwxyz234567"
	masked := MaskLabel(raw)

	if len(masked) <= len(raw) {
		t.Fatalf("expected masked string to contain delimiters: %s", masked)
	}

	// Verify that b32Decode transparently decodes the masked string
	decoded, err := b32Decode(nil, []byte(masked))
	if err != nil {
		t.Fatalf("b32Decode failed to decode masked label: %v", err)
	}

	origDecoded, err := b32Decode(nil, []byte(raw))
	if err != nil {
		t.Fatalf("b32Decode failed on raw: %v", err)
	}

	if !bytes.Equal(decoded, origDecoded) {
		t.Fatalf("decoded masked mismatch: got %x, want %x", decoded, origDecoded)
	}
}
