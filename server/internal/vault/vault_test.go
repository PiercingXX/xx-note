package vault

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("new store: %v", err)
	}
	return s
}

// TestTraversalCorpus throws the classic path-traversal payloads at the
// resolver and asserts none of them ever produce a path that leaves the user's
// vault root. This is the acceptance test for the isolation choke point.
func TestTraversalCorpus(t *testing.T) {
	s := newStore(t)
	userRoot := filepath.Join(s.Root(), "users", "alice", "vault")

	payloads := []string{
		"../evil",
		"../../etc/passwd",
		"../../../../../../etc/shadow",
		"..%2Fetc%2Fpasswd",       // literal percent-encoded (double-decode defense)
		"%2e%2e%2fescape",         // encoded ../
		"%2E%2E/escape",           // mixed-case encoded
		"a/../../../escape",       // climbs above root mid-path
		"..\\..\\windows",         // backslash separators
		"a/../b/../c",             // stays inside but noisy
		"/etc/passwd",             // absolute
		"//etc/passwd",            // double-slash absolute
		"vault/../../other/vault", // reach a sibling user
		"../bob/vault/secret.md",  // reach another user's vault explicitly
		"note\x00.md",             // NUL byte
		strings.Repeat("a", 300),  // over-long single segment
	}

	for _, p := range payloads {
		abs, logical, err := s.ResolveUserPath("alice", p)
		if err != nil {
			continue // rejected outright — good
		}
		// If accepted, it MUST have been contained: no ".." survives and the
		// absolute target stays inside alice's vault root.
		if strings.Contains(logical, "..") {
			t.Errorf("payload %q produced logical path containing ..: %q", p, logical)
		}
		if abs != userRoot && !strings.HasPrefix(abs, userRoot+string(os.PathSeparator)) {
			t.Errorf("payload %q escaped user root: abs=%q root=%q", p, abs, userRoot)
		}
	}
}

// TestValidateRelBasics pins the lexical cleaner's behavior.
func TestValidateRelBasics(t *testing.T) {
	cases := []struct{ in, want string }{
		{"", "/"},
		{"a/b.md", "/a/b.md"},
		{"a/b/../c", "/a/c"},
		{"../../../../", "/"},
		{"./a", "/a"},
	}
	for _, c := range cases {
		got, err := ValidateRel(c.in)
		if err != nil {
			t.Errorf("ValidateRel(%q) unexpected error: %v", c.in, err)
			continue
		}
		if got != c.want {
			t.Errorf("ValidateRel(%q) = %q, want %q", c.in, got, c.want)
		}
	}
	for _, bad := range []string{"a\x00b", "a\\b", "a%2e%2eb", "a%2fb"} {
		if _, err := ValidateRel(bad); err == nil {
			t.Errorf("ValidateRel(%q) should reject", bad)
		}
	}
}

// TestSymlinkEscape ensures a symlink planted inside the tree cannot be used to
// read outside content: resolution through a symlink component is refused.
func TestSymlinkEscape(t *testing.T) {
	s := newStore(t)
	userRoot := filepath.Join(s.Root(), "users", "alice", "vault")
	if err := os.MkdirAll(userRoot, 0o700); err != nil {
		t.Fatal(err)
	}
	// A secret file outside every vault.
	outside := filepath.Join(s.Root(), "outside-secret.txt")
	if err := os.WriteFile(outside, []byte("TOP SECRET"), 0o600); err != nil {
		t.Fatal(err)
	}
	// Plant a symlink inside alice's vault pointing at the data root.
	link := filepath.Join(userRoot, "escape")
	if err := os.Symlink(s.Root(), link); err != nil {
		t.Skipf("symlinks unsupported here: %v", err)
	}
	// Reading through the symlink must be refused by the resolver.
	if _, _, err := s.ResolveUserPath("alice", "escape/outside-secret.txt"); err == nil {
		t.Fatal("resolution through a symlink component must be refused")
	}
	if _, _, err := s.Get("alice", "escape/outside-secret.txt"); err == nil {
		t.Fatal("Get through a symlink component must fail")
	}
}

// TestUserIDAsPathSegment rejects a user id that could itself escape users/.
func TestUserIDAsPathSegment(t *testing.T) {
	s := newStore(t)
	for _, bad := range []string{"", "..", ".", "a/b", "a\\b", "x\x00y"} {
		if _, _, err := s.ResolveUserPath(bad, "note.md"); err == nil {
			t.Errorf("user id %q must be rejected", bad)
		}
	}
}

// TestRoundTripCRUD exercises the normal note lifecycle and ETag semantics.
func TestRoundTripCRUD(t *testing.T) {
	s := newStore(t)
	uid := "alice"

	// Empty vault lists empty.
	if ents, err := s.List(uid, "/"); err != nil || len(ents) != 0 {
		t.Fatalf("empty list = (%v,%v), want (empty,nil)", ents, err)
	}
	// Missing note is ErrNotFound.
	if _, _, err := s.Get(uid, "note.md"); err != ErrNotFound {
		t.Fatalf("get missing = %v, want ErrNotFound", err)
	}
	// Create.
	etag1, err := s.Put(uid, "01J-note.md", []byte("hello"), PutIfAbsent, "")
	if err != nil {
		t.Fatalf("put-if-absent: %v", err)
	}
	// PutIfAbsent again must conflict.
	if _, err := s.Put(uid, "01J-note.md", []byte("x"), PutIfAbsent, ""); err != ErrConflict {
		t.Fatalf("second put-if-absent = %v, want ErrConflict", err)
	}
	// If-Match with stale etag conflicts.
	if _, err := s.Put(uid, "01J-note.md", []byte("y"), PutIfMatch, `"stale"`); err != ErrConflict {
		t.Fatalf("if-match stale = %v, want ErrConflict", err)
	}
	// If-Match with correct etag succeeds and changes the etag.
	etag2, err := s.Put(uid, "01J-note.md", []byte("world"), PutIfMatch, etag1)
	if err != nil {
		t.Fatalf("if-match good: %v", err)
	}
	if etag2 == etag1 {
		t.Fatal("etag must change after a write")
	}
	// Read back.
	data, etag3, err := s.Get(uid, "01J-note.md")
	if err != nil || string(data) != "world" || etag3 != etag2 {
		t.Fatalf("get = (%q,%q,%v), want (world,%q,nil)", data, etag3, err, etag2)
	}
	// mkcol + move (rename).
	if err := s.Mkcol(uid, "attachments"); err != nil {
		t.Fatalf("mkcol: %v", err)
	}
	if err := s.Mkcol(uid, "attachments"); err != nil {
		t.Fatalf("mkcol idempotent: %v", err)
	}
	if err := s.Move(uid, "01J-note.md", "01J-renamed.md", true); err != nil {
		t.Fatalf("move: %v", err)
	}
	// Move with overwrite=false onto an existing dest is refused.
	if _, err := s.Put(uid, "keep.md", []byte("k"), PutUnconditional, ""); err != nil {
		t.Fatal(err)
	}
	if err := s.Move(uid, "keep.md", "01J-renamed.md", false); err != ErrDestExists {
		t.Fatalf("move no-overwrite onto existing = %v, want ErrDestExists", err)
	}
	// Delete.
	if err := s.Delete(uid, "01J-renamed.md"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if err := s.Delete(uid, "01J-renamed.md"); err != ErrNotFound {
		t.Fatalf("delete missing = %v, want ErrNotFound", err)
	}
}
