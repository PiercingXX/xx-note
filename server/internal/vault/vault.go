// Package vault is the plain-files-on-disk store for xx-note. Each user's notes
// live as .md files (plus attachments/ and other subpaths) under
//
//	<dataDir>/users/<user_id>/vault/...
//
// exactly the vault-of-markdown-files shape the Android app already syncs, so an
// operator with shell access can cat a user's notes directly.
//
// SECURITY — the one rule: every path that originates from a request passes
// through ResolveUserPath exactly once, and the <user_id> it is joined under
// comes ONLY from a validated fabric token, never from any client-supplied
// field. ResolveUserPath rejects traversal (../), absolute paths, NUL bytes,
// backslashes, percent-encoded separators/dots, over-long segments, and any
// symlink component, and verifies the final lexical target stays inside the
// user's vault root. This is the single choke point; no handler ever builds a
// path any other way.
//
// Only the Go standard library is used.
package vault

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

var (
	ErrNotFound    = errors.New("not found")
	ErrExists      = errors.New("already exists")
	ErrInvalid     = errors.New("invalid path")
	ErrConflict    = errors.New("etag precondition failed")
	ErrIsDir       = errors.New("path is a directory")
	ErrDestExists  = errors.New("destination exists")
	ErrUserMissing = errors.New("user id required")
)

const maxSegment = 255
const maxPathLen = 4096

// Store is the whole-service file store. One process, one data root.
type Store struct {
	root string // absolute, symlink-resolved data root
}

// New creates (or opens) the data root and its users/ subtree.
func New(dataDir string) (*Store, error) {
	abs, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Join(abs, "users"), 0o700); err != nil {
		return nil, err
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, err
	}
	return &Store{root: real}, nil
}

// Root returns the resolved data root (tests only).
func (s *Store) Root() string { return s.root }

// Entry is one item of a directory listing.
type Entry struct {
	Name string `json:"name"`
	ETag string `json:"etag"`
	Size int64  `json:"size"`
	Dir  bool   `json:"dir"`
}

// ValidateRel cleans and vets a vault-relative request path. It is the lexical
// half of the containment check (the filesystem/symlink half is in
// ResolveUserPath). Adapted from xx-drive's fsdrv.ValidateRel.
func ValidateRel(rel string) (string, error) {
	if strings.ContainsRune(rel, 0) {
		return "", ErrInvalid
	}
	if rel == "" {
		return "/", nil
	}
	// Reject Windows separators explicitly so `a\..\..` cannot smuggle a
	// separator through on Unix.
	if strings.Contains(rel, "\\") {
		return "", ErrInvalid
	}
	// Reject percent-encoded separators/dots in any segment. net/http decodes
	// the path once, so these are literal characters here — a classic
	// double-decode confusion vector downstream. Cheap to forbid.
	lower := strings.ToLower(rel)
	if strings.Contains(lower, "%2f") || strings.Contains(lower, "%5c") || strings.Contains(lower, "%2e") {
		return "", ErrInvalid
	}
	clean := filepath.Clean("/" + rel) // anchors and cleans; ".." above root collapses to "/"
	if clean == "." || !strings.HasPrefix(clean, "/") {
		return "/", nil
	}
	for _, seg := range strings.Split(clean, "/") {
		switch seg {
		case "", ".", "..":
			continue
		}
		if len(seg) > maxSegment {
			return "", ErrInvalid
		}
	}
	if len(clean) > maxPathLen {
		return "", ErrInvalid
	}
	return clean, nil
}

// ResolveUserPath is THE choke point. userID must come from a validated token.
// It returns the absolute on-disk path for (userID, rel) after proving the
// target stays inside <root>/users/<userID>/vault, or ErrInvalid.
func (s *Store) ResolveUserPath(userID, rel string) (abs string, logical string, err error) {
	if userID == "" {
		return "", "", ErrUserMissing
	}
	// The user id itself must be a single safe path segment — it is opaque
	// token_hex(16) from the fabric identity, so reject anything that could
	// escape the users/ dir even though it is server-derived, not client input.
	if userID == "." || userID == ".." || strings.ContainsAny(userID, "/\\") || strings.ContainsRune(userID, 0) {
		return "", "", ErrInvalid
	}
	logical, err = ValidateRel(rel)
	if err != nil {
		return "", "", err
	}
	userRoot := filepath.Join(s.root, "users", userID, "vault")
	abs = filepath.Join(userRoot, filepath.FromSlash(logical))
	// Lexical containment: abs must be the user root or strictly beneath it.
	if abs != userRoot && !strings.HasPrefix(abs, userRoot+string(os.PathSeparator)) {
		return "", "", ErrInvalid
	}
	// Walk components from the user root down, refusing any symlink component
	// so a planted link cannot bridge outside the tree.
	cur := userRoot
	for _, seg := range strings.Split(strings.Trim(logical, "/"), "/") {
		if seg == "" {
			continue
		}
		next := filepath.Join(cur, seg)
		fi, lerr := os.Lstat(next)
		if lerr != nil {
			if errors.Is(lerr, os.ErrNotExist) {
				return abs, logical, nil // rest of the path does not exist yet
			}
			return "", "", lerr
		}
		if fi.Mode()&os.ModeSymlink != 0 {
			return "", "", ErrInvalid
		}
		if !fi.IsDir() {
			return abs, logical, nil // a file component; caller gets its own NotFound below it
		}
		cur = next
	}
	return abs, logical, nil
}

func etagOf(b []byte) string {
	sum := sha256.Sum256(b)
	return `"` + hex.EncodeToString(sum[:]) + `"`
}

// List returns a Depth:1 listing of the directory at rel within the user's vault.
// A missing vault root reads as an empty listing (a fresh user has no notes yet).
func (s *Store) List(userID, rel string) ([]Entry, error) {
	abs, _, err := s.ResolveUserPath(userID, rel)
	if err != nil {
		return nil, err
	}
	fi, err := os.Stat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return []Entry{}, nil
		}
		return nil, err
	}
	if !fi.IsDir() {
		return nil, ErrInvalid
	}
	ents, err := os.ReadDir(abs)
	if err != nil {
		return nil, err
	}
	out := make([]Entry, 0, len(ents))
	for _, e := range ents {
		info, ierr := e.Info()
		if ierr != nil {
			continue
		}
		en := Entry{Name: e.Name(), Dir: e.IsDir(), Size: info.Size()}
		if !e.IsDir() {
			if data, rerr := os.ReadFile(filepath.Join(abs, e.Name())); rerr == nil {
				en.ETag = etagOf(data)
			}
		}
		out = append(out, en)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

// Get returns the whole-file bytes and its ETag, or ErrNotFound.
func (s *Store) Get(userID, rel string) ([]byte, string, error) {
	abs, _, err := s.ResolveUserPath(userID, rel)
	if err != nil {
		return nil, "", err
	}
	fi, err := os.Stat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, "", ErrNotFound
		}
		return nil, "", err
	}
	if fi.IsDir() {
		return nil, "", ErrIsDir
	}
	data, err := os.ReadFile(abs)
	if err != nil {
		return nil, "", err
	}
	return data, etagOf(data), nil
}

// PutMode selects the conditional-write semantics for Put.
type PutMode int

const (
	// PutUnconditional overwrites (or creates) regardless of current state.
	PutUnconditional PutMode = iota
	// PutIfMatch requires the current file's ETag to equal ifMatch.
	PutIfMatch
	// PutIfAbsent requires the file to not already exist (If-None-Match: *).
	PutIfAbsent
)

// Put writes bytes at rel, honoring the conditional mode, and returns the new
// ETag. It creates parent directories as needed and writes atomically
// (temp-then-rename). ErrConflict signals a failed precondition (HTTP 412).
func (s *Store) Put(userID, rel string, data []byte, mode PutMode, ifMatch string) (string, error) {
	abs, logical, err := s.ResolveUserPath(userID, rel)
	if err != nil {
		return "", err
	}
	if logical == "/" {
		return "", ErrInvalid // cannot write the vault root itself
	}
	existing, statErr := os.Stat(abs)
	switch {
	case statErr == nil && existing.IsDir():
		return "", ErrIsDir
	case mode == PutIfAbsent && statErr == nil:
		return "", ErrConflict
	case mode == PutIfMatch:
		if statErr != nil {
			// If-Match against a nonexistent file cannot succeed.
			return "", ErrConflict
		}
		cur, rerr := os.ReadFile(abs)
		if rerr != nil {
			return "", rerr
		}
		if etagOf(cur) != ifMatch {
			return "", ErrConflict
		}
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o700); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(filepath.Dir(abs), ".xxnote-tmp-*")
	if err != nil {
		return "", err
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return "", err
	}
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return "", err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	if err := os.Rename(tmpName, abs); err != nil {
		os.Remove(tmpName)
		return "", err
	}
	return etagOf(data), nil
}

// Delete removes the file at rel. ErrNotFound if it is absent.
func (s *Store) Delete(userID, rel string) error {
	abs, logical, err := s.ResolveUserPath(userID, rel)
	if err != nil {
		return err
	}
	if logical == "/" {
		return ErrInvalid
	}
	fi, err := os.Lstat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return ErrNotFound
		}
		return err
	}
	if fi.IsDir() {
		return ErrIsDir
	}
	return os.Remove(abs)
}

// Mkcol creates the directory at rel (idempotent — already-exists is success).
func (s *Store) Mkcol(userID, rel string) error {
	abs, _, err := s.ResolveUserPath(userID, rel)
	if err != nil {
		return err
	}
	if fi, serr := os.Stat(abs); serr == nil {
		if fi.IsDir() {
			return nil
		}
		return ErrExists
	}
	return os.MkdirAll(abs, 0o700)
}

// Move renames from -> to. When overwrite is false, an existing destination is
// refused with ErrDestExists (RFC 4918 Overwrite: F). Both paths pass through
// the choke point, so neither can escape the user's vault.
func (s *Store) Move(userID, fromRel, toRel string, overwrite bool) error {
	fromAbs, fromLogical, err := s.ResolveUserPath(userID, fromRel)
	if err != nil {
		return err
	}
	toAbs, toLogical, err := s.ResolveUserPath(userID, toRel)
	if err != nil {
		return err
	}
	if fromLogical == "/" || toLogical == "/" {
		return ErrInvalid
	}
	if _, err := os.Lstat(fromAbs); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return ErrNotFound
		}
		return err
	}
	if _, err := os.Lstat(toAbs); err == nil {
		if !overwrite {
			return ErrDestExists
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(toAbs), 0o700); err != nil {
		return err
	}
	return os.Rename(fromAbs, toAbs)
}
