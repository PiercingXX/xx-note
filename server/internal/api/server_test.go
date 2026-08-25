package api

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"xxnote-server/internal/fabric"
	"xxnote-server/internal/vault"
)

// --- test rig: a real ring + minter reproducing the v1 format ---------------

var testKeyID = "00aa00bb00cc00dd"
var testSecret = bytes.Repeat([]byte{0x5a}, 32)
var testNow = time.Unix(1700000000, 0)

func mintToken(userID string, exp float64) string {
	claims := map[string]any{"user_id": userID, "jti": "t", "iat": 1700000000.0, "exp": exp}
	payload, _ := json.Marshal(claims)
	body := base64.RawURLEncoding.EncodeToString(payload)
	signedPart := "v1." + testKeyID + "." + body
	mac := hmac.New(sha256.New, testSecret)
	mac.Write([]byte(signedPart))
	sig := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return signedPart + "." + sig
}

func newTestServer(t *testing.T) (*httptest.Server, *vault.Store, string) {
	t.Helper()
	dir := t.TempDir()
	vs, err := vault.New(dir)
	if err != nil {
		t.Fatal(err)
	}
	ring := fabric.NewKeyring(map[string][]byte{testKeyID: testSecret})
	srv := New(Config{nowFunc: func() time.Time { return testNow }}, ring, vs)
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return ts, vs, dir
}

// validExp is 30 days after testNow — every good token uses it.
const validExp = 1700000000.0 + 30*24*3600

func do(t *testing.T, ts *httptest.Server, method, path, token string, body []byte, headers map[string]string) *http.Response {
	t.Helper()
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, ts.URL+path, rdr)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

// TestHealthz — the health probe needs no auth.
func TestHealthz(t *testing.T) {
	ts, _, _ := newTestServer(t)
	resp := do(t, ts, "GET", "/healthz", "", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("healthz = %d, want 200", resp.StatusCode)
	}
}

// TestUnauthenticatedRejected — no/invalid token is 401 on every guarded route.
func TestUnauthenticatedRejected(t *testing.T) {
	ts, _, _ := newTestServer(t)
	routes := []struct{ method, path string }{
		{"GET", "/api/v1/list"},
		{"GET", "/api/v1/file/note.md"},
		{"PUT", "/api/v1/file/note.md"},
		{"DELETE", "/api/v1/file/note.md"},
		{"POST", "/api/v1/move"},
		{"POST", "/api/v1/mkcol"},
	}
	for _, rt := range routes {
		// no token
		resp := do(t, ts, rt.method, rt.path, "", []byte("{}"), nil)
		resp.Body.Close()
		if resp.StatusCode != 401 {
			t.Errorf("%s %s no-token = %d, want 401", rt.method, rt.path, resp.StatusCode)
		}
		// garbage token
		resp = do(t, ts, rt.method, rt.path, "not.a.real.token", []byte("{}"), nil)
		resp.Body.Close()
		if resp.StatusCode != 401 {
			t.Errorf("%s %s bad-token = %d, want 401", rt.method, rt.path, resp.StatusCode)
		}
	}
}

// TestExpiredTokenRejected — an expired token is 401.
func TestExpiredTokenRejected(t *testing.T) {
	ts, _, _ := newTestServer(t)
	expired := mintToken("alice", 1700000000.0-1) // exp before testNow
	resp := do(t, ts, "GET", "/api/v1/list", expired, nil, nil)
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("expired token = %d, want 401", resp.StatusCode)
	}
}

// TestNoteLifecycleOverHTTP — the RemoteFiles endpoint contract end to end.
func TestNoteLifecycleOverHTTP(t *testing.T) {
	ts, _, _ := newTestServer(t)
	tok := mintToken("useralice", validExp)

	// create-only write (putIfAbsent)
	resp := do(t, ts, "PUT", "/api/v1/file/01J-a.md", tok, []byte("# hello"), map[string]string{"If-None-Match": "*"})
	if resp.StatusCode != 200 {
		t.Fatalf("putIfAbsent = %d, want 200", resp.StatusCode)
	}
	var pr struct{ ETag string }
	json.NewDecoder(resp.Body).Decode(&pr)
	resp.Body.Close()
	if pr.ETag == "" {
		t.Fatal("put response missing etag")
	}
	// putIfAbsent again → 412
	resp = do(t, ts, "PUT", "/api/v1/file/01J-a.md", tok, []byte("x"), map[string]string{"If-None-Match": "*"})
	resp.Body.Close()
	if resp.StatusCode != 412 {
		t.Fatalf("second putIfAbsent = %d, want 412", resp.StatusCode)
	}
	// get it back
	resp = do(t, ts, "GET", "/api/v1/file/01J-a.md", tok, nil, nil)
	data, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || string(data) != "# hello" {
		t.Fatalf("get = (%d,%q), want (200,# hello)", resp.StatusCode, data)
	}
	etag := resp.Header.Get("ETag")
	// conditional update with the right etag
	resp = do(t, ts, "PUT", "/api/v1/file/01J-a.md", tok, []byte("# world"), map[string]string{"If-Match": etag})
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("if-match update = %d, want 200", resp.StatusCode)
	}
	// conditional update with a stale etag → 412
	resp = do(t, ts, "PUT", "/api/v1/file/01J-a.md", tok, []byte("nope"), map[string]string{"If-Match": etag})
	resp.Body.Close()
	if resp.StatusCode != 412 {
		t.Fatalf("stale if-match = %d, want 412", resp.StatusCode)
	}
	// list shows the note
	resp = do(t, ts, "GET", "/api/v1/list?dir=/", tok, nil, nil)
	var lr struct{ Entries []vault.Entry }
	json.NewDecoder(resp.Body).Decode(&lr)
	resp.Body.Close()
	if len(lr.Entries) != 1 || lr.Entries[0].Name != "01J-a.md" {
		t.Fatalf("list = %+v, want one 01J-a.md", lr.Entries)
	}
	// move (rename)
	mv, _ := json.Marshal(map[string]any{"from": "01J-a.md", "to": "01J-b.md"})
	resp = do(t, ts, "POST", "/api/v1/move", tok, mv, nil)
	resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("move = %d, want 204", resp.StatusCode)
	}
	// mkcol
	mk, _ := json.Marshal(map[string]any{"dir": "attachments"})
	resp = do(t, ts, "POST", "/api/v1/mkcol", tok, mk, nil)
	resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("mkcol = %d, want 204", resp.StatusCode)
	}
	// delete
	resp = do(t, ts, "DELETE", "/api/v1/file/01J-b.md", tok, nil, nil)
	resp.Body.Close()
	if resp.StatusCode != 204 {
		t.Fatalf("delete = %d, want 204", resp.StatusCode)
	}
	resp = do(t, ts, "GET", "/api/v1/file/01J-b.md", tok, nil, nil)
	resp.Body.Close()
	if resp.StatusCode != 404 {
		t.Fatalf("get deleted = %d, want 404", resp.StatusCode)
	}
}

// TestTwoUserIsolation is THE deliverable proof: user A's token can never
// read, overwrite, move-away, or delete user B's vault, and B's sentinel note
// never appears in ANY response A receives across the whole endpoint matrix —
// including paths crafted to alias into B's tree.
func TestTwoUserIsolation(t *testing.T) {
	ts, vs, dataDir := newTestServer(t)

	const uidA = "aaaa1111aaaa1111aaaa1111aaaa1111"
	const uidB = "bbbb2222bbbb2222bbbb2222bbbb2222"
	tokA := mintToken(uidA, validExp)
	tokB := mintToken(uidB, validExp)

	const sentinel = "TOP-SECRET-B-CONTENT-b9f3"

	// B writes a private note through the real API.
	resp := do(t, ts, "PUT", "/api/v1/file/secret.md", tokB, []byte(sentinel), nil)
	if resp.StatusCode != 200 {
		t.Fatalf("B write = %d, want 200", resp.StatusCode)
	}
	resp.Body.Close()

	// Confirm it really landed under B's vault on disk (and not A's).
	bOnDisk := filepath.Join(vs.Root(), "users", uidB, "vault", "secret.md")
	if _, err := os.Stat(bOnDisk); err != nil {
		t.Fatalf("B note not on disk where expected: %v", err)
	}

	// A batch of paths A will try, all intended to reach B's secret.md. The
	// relative-depth ones are counted to GENUINELY resolve into B's vault when
	// containment is absent: from ROOT/users/A/vault, "../../<uidB>/vault" climbs
	// vault -> A -> users, then descends into B. A correct resolver rejects all.
	crossPaths := []string{
		"secret.md",                                   // same name, must resolve under A, not B
		"../../" + uidB + "/vault/secret.md",          // the genuinely-reaching traversal
		"..%2f..%2f" + uidB + "%2fvault%2fsecret.md",  // percent-encoded form
		"../" + uidB + "/vault/secret.md",             // one-short (lands in A's child)
		"/users/" + uidB + "/vault/secret.md",         // absolute
		"note\x00/../../" + uidB + "/vault/secret.md", // NUL smuggling
	}

	// Store-level cross-user check FIRST. This is the isolation proof that is
	// directly sensitive to the containment choke point: it hits the vault store
	// with A's user_id and each hostile path, bypassing net/http's own URL
	// cleaning, and asserts A can never read B's bytes nor overwrite/delete B's
	// file. (The HTTP matrix below adds the wire layer on top.)
	for _, p := range crossPaths {
		if data, _, err := vs.Get(uidA, p); err == nil && strings.Contains(string(data), sentinel) {
			t.Fatalf("ISOLATION BREACH (store GET): A read B's secret via %q", p)
		}
		if _, err := vs.Put(uidA, p, []byte("A-OVERWRITE"), vault.PutUnconditional, ""); err == nil {
			// A write that "succeeded" must not have touched B's file.
			if cur, rerr := os.ReadFile(bOnDisk); rerr == nil && string(cur) != sentinel {
				t.Fatalf("ISOLATION BREACH (store PUT): A overwrote B's secret via %q", p)
			}
		}
		if err := vs.Delete(uidA, p); err == nil {
			if _, serr := os.Stat(bOnDisk); os.IsNotExist(serr) {
				t.Fatalf("ISOLATION BREACH (store DELETE): A deleted B's secret via %q", p)
			}
		}
	}
	// Store-level cross-user move attempts must never touch B's tree either.
	if err := vs.Move(uidA, "../"+uidB+"/vault/secret.md", "stolen.md", true); err == nil {
		if _, serr := os.Stat(bOnDisk); os.IsNotExist(serr) {
			t.Fatal("ISOLATION BREACH (store MOVE): A moved B's secret out of B's vault")
		}
	}

	assertNoSentinel := func(where string, resp *http.Response) {
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if strings.Contains(string(body), sentinel) {
			t.Fatalf("ISOLATION BREACH: A saw B's secret via %s (status %d): %q", where, resp.StatusCode, body)
		}
	}

	for _, p := range crossPaths {
		if strings.ContainsRune(p, 0) {
			continue // a NUL byte cannot even be placed in a URL — that is itself a defense; covered at the store level above
		}
		// GET must never return B's content.
		r := do(t, ts, "GET", "/api/v1/file/"+p, tokA, nil, nil)
		assertNoSentinel("GET "+p, r)

		// PUT as A must never overwrite B's file. Verify B's bytes unchanged after.
		r = do(t, ts, "PUT", "/api/v1/file/"+p, tokA, []byte("A-OVERWRITE-ATTEMPT"), nil)
		r.Body.Close()

		// DELETE as A must never remove B's file.
		r = do(t, ts, "DELETE", "/api/v1/file/"+p, tokA, nil, nil)
		r.Body.Close()
	}

	// Move attempts: A tries to move B's secret into A's own tree, and to move
	// something over B's secret.
	for _, mv := range []map[string]any{
		{"from": "../" + uidB + "/vault/secret.md", "to": "stolen.md"},
		{"from": "secret.md", "to": "../" + uidB + "/vault/secret.md"},
	} {
		body, _ := json.Marshal(mv)
		r := do(t, ts, "POST", "/api/v1/move", tokA, body, nil)
		r.Body.Close()
	}

	// After every hostile attempt, B's file must still hold the ORIGINAL bytes.
	got, err := os.ReadFile(bOnDisk)
	if err != nil {
		t.Fatalf("B note vanished after A's attempts: %v", err)
	}
	if string(got) != sentinel {
		t.Fatalf("B note was mutated by A: %q", got)
	}

	// A's listing must never include B's secret, and A must have a stolen.md
	// only if it resolved inside A (it must not contain B's bytes anyway).
	r := do(t, ts, "GET", "/api/v1/list?dir=/", tokA, nil, nil)
	assertNoSentinel("A list", r)

	// Sanity: B can still read its own note through the API.
	r = do(t, ts, "GET", "/api/v1/file/secret.md", tokB, nil, nil)
	bBody, _ := io.ReadAll(r.Body)
	r.Body.Close()
	if r.StatusCode != 200 || string(bBody) != sentinel {
		t.Fatalf("B lost access to its own note: (%d,%q)", r.StatusCode, bBody)
	}

	// And confirm nothing leaked into A's tree that carries B's bytes.
	aRoot := filepath.Join(vs.Root(), "users", uidA, "vault")
	_ = dataDir
	filepath.Walk(aRoot, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return nil
		}
		data, _ := os.ReadFile(path)
		if strings.Contains(string(data), sentinel) {
			t.Fatalf("ISOLATION BREACH: B's content found inside A's tree at %s", path)
		}
		return nil
	})
}

// TestForgedUserIdClaimIgnored — a token minted with a different signing key
// but claiming another user_id must be rejected outright (HMAC), never honored.
func TestForgedTokenRejected(t *testing.T) {
	ts, _, _ := newTestServer(t)
	// forge with a wrong secret
	claims := map[string]any{"user_id": "victim", "jti": "t", "iat": 1700000000.0, "exp": validExp}
	payload, _ := json.Marshal(claims)
	body := base64.RawURLEncoding.EncodeToString(payload)
	signedPart := "v1." + testKeyID + "." + body
	mac := hmac.New(sha256.New, bytes.Repeat([]byte{0x00}, 32)) // wrong key
	mac.Write([]byte(signedPart))
	forged := signedPart + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	resp := do(t, ts, "GET", "/api/v1/list", forged, nil, nil)
	resp.Body.Close()
	if resp.StatusCode != 401 {
		t.Fatalf("forged token = %d, want 401", resp.StatusCode)
	}
}
