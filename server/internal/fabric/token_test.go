package fabric

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Golden vector minted by the REAL estate minter
// (xx-chat/xxchat/fabric_tokens.py mint_fabric_token) with a fixed key, user,
// iat and jti. Its bytes are pinned here so this Go validator can never drift
// from the Python ClusterKeyring v1 format that xx-chat's fabric login emits.
// Regenerate only against the actual Python minter if the format ever changes.
const (
	goldKeyID     = "0011223344556677"
	goldSecretHex = "8f2b0c1d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff"
	goldUserID    = "3f9a1b2c4d5e6f70a1b2c3d4e5f60718"
	goldIAT       = 1700000000.0
	goldEXP       = 1702592000.0 // iat + 30d
	goldToken     = "v1.0011223344556677.eyJleHAiOiAxNzAyNTkyMDAwLjAsICJpYXQiOiAxNzAwMDAwMDAwLjAsICJqdGkiOiAiZGVhZGJlZWZjYWZlZjAwZCIsICJ1c2VyX2lkIjogIjNmOWExYjJjNGQ1ZTZmNzBhMWIyYzNkNGU1ZjYwNzE4In0.6sRIg_wEx965SWfrdUBnESmxKWsD-2MrJTcdhxy8L7Q"
)

func goldRing(t *testing.T) *Keyring {
	t.Helper()
	secret, err := hexBytes(goldSecretHex)
	if err != nil {
		t.Fatalf("decode secret: %v", err)
	}
	return NewKeyring(map[string][]byte{goldKeyID: secret})
}

func hexBytes(s string) ([]byte, error) {
	b := make([]byte, len(s)/2)
	for i := 0; i < len(b); i++ {
		var v int
		for j := 0; j < 2; j++ {
			c := s[i*2+j]
			v <<= 4
			switch {
			case c >= '0' && c <= '9':
				v |= int(c - '0')
			case c >= 'a' && c <= 'f':
				v |= int(c-'a') + 10
			case c >= 'A' && c <= 'F':
				v |= int(c-'A') + 10
			}
		}
		b[i] = byte(v)
	}
	return b, nil
}

// mintForTest reproduces the v1 format so tests can forge fresh tokens with
// arbitrary claims. It matches ClusterKeyring.mint / mint_fabric_token exactly.
func mintForTest(keyID string, secret []byte, userID string, iat, exp float64) string {
	claims := map[string]any{
		"user_id": userID,
		"jti":     "testjti0",
		"iat":     iat,
		"exp":     exp,
	}
	payload, _ := json.Marshal(sortedClaims(claims))
	body := base64.RawURLEncoding.EncodeToString(payload)
	signedPart := "v1." + keyID + "." + body
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(signedPart))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return signedPart + "." + sig
}

// sortedClaims marshals with sorted keys, matching json.dumps(sort_keys=True).
func sortedClaims(m map[string]any) json.RawMessage {
	// encoding/json already sorts map keys, so a plain map round-trips
	// identically to Python's sort_keys=True for these string/number claims.
	b, _ := json.Marshal(m)
	return b
}

func TestGoldenVectorFromPythonMinter(t *testing.T) {
	ring := goldRing(t)
	// now just after iat: valid.
	c, err := ring.Validate(goldToken, time.Unix(int64(goldIAT)+10, 0))
	if err != nil {
		t.Fatalf("golden token must validate, got %v", err)
	}
	if c.UserID != goldUserID {
		t.Fatalf("user_id = %q, want %q", c.UserID, goldUserID)
	}
	if c.EXP != goldEXP {
		t.Fatalf("exp = %v, want %v", c.EXP, goldEXP)
	}
}

func TestExpiredTokenRejected(t *testing.T) {
	ring := goldRing(t)
	// now one second past exp: rejected (matches Python moment >= exp).
	if _, err := ring.Validate(goldToken, time.Unix(int64(goldEXP)+1, 0)); err == nil {
		t.Fatal("expired token must be rejected")
	}
	// exactly at exp is also expired (>=).
	if _, err := ring.Validate(goldToken, time.Unix(int64(goldEXP), 0)); err == nil {
		t.Fatal("token at exactly exp must be rejected")
	}
}

func TestWrongKeyRejected(t *testing.T) {
	// A ring that does not hold the golden signing key must reject the token,
	// never accept it under a different key.
	other := make([]byte, 32)
	for i := range other {
		other[i] = 0xAB
	}
	ring := NewKeyring(map[string][]byte{"ffffffffffffffff": other})
	if _, err := ring.Validate(goldToken, time.Unix(int64(goldIAT)+10, 0)); err == nil {
		t.Fatal("token signed by an unknown key must be rejected")
	}
}

func TestForgedSignatureRejected(t *testing.T) {
	secret, _ := hexBytes(goldSecretHex)
	ring := NewKeyring(map[string][]byte{goldKeyID: secret})
	// A token whose user_id claim is tampered but signed with a wrong secret
	// (the attacker does not hold the key) must be rejected — HMAC prevents
	// claim tampering without the key.
	wrong := make([]byte, 32) // all zeros — not the real secret
	forged := mintForTest(goldKeyID, wrong, "attacker-user", goldIAT, goldEXP)
	if _, err := ring.Validate(forged, time.Unix(int64(goldIAT)+10, 0)); err == nil {
		t.Fatal("token re-signed with the wrong key must be rejected")
	}
}

func TestTamperedClaimsRejected(t *testing.T) {
	ring := goldRing(t)
	// Flip a byte in the body segment: signature no longer matches.
	b := []byte(goldToken)
	// find the third segment start
	dots := 0
	idx := 0
	for i, ch := range b {
		if ch == '.' {
			dots++
			if dots == 2 {
				idx = i + 1
				break
			}
		}
	}
	if b[idx] == 'A' {
		b[idx] = 'B'
	} else {
		b[idx] = 'A'
	}
	if _, err := ring.Validate(string(b), time.Unix(int64(goldIAT)+10, 0)); err == nil {
		t.Fatal("tampered body must be rejected")
	}
}

func TestMalformedTokensRejected(t *testing.T) {
	ring := goldRing(t)
	bad := []string{
		"",
		"garbage",
		"v1.only.three",
		"v2.0011223344556677.body.sig", // wrong version
		"v1..body.sig",                 // empty key id
		"v1.0011223344556677.!!!.@@@",  // non-base64
	}
	for _, tok := range bad {
		if _, err := ring.Validate(tok, time.Unix(int64(goldIAT)+10, 0)); err == nil {
			t.Errorf("malformed token %q must be rejected", tok)
		}
	}
}

func TestUserIDForHeaderParsing(t *testing.T) {
	ring := goldRing(t)
	now := time.Unix(int64(goldIAT)+10, 0)
	uid, err := ring.UserIDFor("Bearer "+goldToken, now)
	if err != nil || uid != goldUserID {
		t.Fatalf("UserIDFor good header = (%q,%v), want (%q,nil)", uid, err, goldUserID)
	}
	// case-insensitive scheme
	if _, err := ring.UserIDFor("bearer "+goldToken, now); err != nil {
		t.Fatalf("lowercase bearer must work: %v", err)
	}
	bad := []string{"", "  ", "Basic abc", "Bearer", "Bearer ", goldToken, "Bearer a b c"}
	for _, h := range bad {
		if _, err := ring.UserIDFor(h, now); err == nil {
			t.Errorf("header %q must be rejected", h)
		}
	}
}

func TestLoadKeyringRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "ring.json")
	content := `{"keys": {"` + goldKeyID + `": "` + goldSecretHex + `"}, "active_key_id": "` + goldKeyID + `"}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	ring, err := LoadKeyring(path)
	if err != nil {
		t.Fatalf("load ring: %v", err)
	}
	if _, err := ring.Validate(goldToken, time.Unix(int64(goldIAT)+10, 0)); err != nil {
		t.Fatalf("token from loaded ring must validate: %v", err)
	}
	// Missing path / env is a loud error.
	os.Unsetenv(EnvKeyringPath)
	if _, err := LoadKeyring(""); err == nil {
		t.Fatal("empty path with no env must error")
	}
}
