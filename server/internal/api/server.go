// Package api wires the HTTP surface onto the fabric-token validator and the
// per-user vault store. The endpoints mirror the Android app's RemoteFiles
// port (app/src/main/java/com/piercingxx/xxnote/sync/Ports.kt) 1:1 so the
// client swap from WebDAV to the fabric backend is a single new RemoteFiles
// implementation.
//
// Every authenticated handler derives the caller's user_id from the validated
// Bearer token ONLY (fabric.Keyring.UserIDFor) and passes it straight into the
// vault choke point. No handler ever reads a user id / username from the URL,
// query, body, or any other header — that is the whole isolation contract.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"xxnote-server/internal/fabric"
	"xxnote-server/internal/vault"
)

// pathPrefixes: /api/v1/file/<vault-relative path...>
const filePrefix = "/api/v1/file/"

// Config configures the server.
type Config struct {
	Addr      string
	MaxBodyMB int64
	nowFunc   func() time.Time // test hook; nil means time.Now
}

// Server holds the validator and store.
type Server struct {
	cfg  Config
	ring *fabric.Keyring
	vs   *vault.Store
	mux  *http.ServeMux
}

// New builds the server and its routes.
func New(cfg Config, ring *fabric.Keyring, vs *vault.Store) *Server {
	if cfg.MaxBodyMB <= 0 {
		cfg.MaxBodyMB = 64
	}
	s := &Server{cfg: cfg, ring: ring, vs: vs, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) now() time.Time {
	if s.cfg.nowFunc != nil {
		return s.cfg.nowFunc()
	}
	return time.Now()
}

// Handler returns the fully wrapped HTTP handler.
func (s *Server) Handler() http.Handler { return s.securityHeaders(s.recover(s.mux)) }

func (s *Server) routes() {
	m := s.mux
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		io.WriteString(w, "ok")
	})

	// RemoteFiles.list
	m.HandleFunc("GET /api/v1/list", s.authed(s.handleList))
	// RemoteFiles.get / getFile  (text and binary share one path space)
	m.HandleFunc("GET "+filePrefix+"{path...}", s.authed(s.handleGet))
	// RemoteFiles.put / putIfAbsent / putFile
	m.HandleFunc("PUT "+filePrefix+"{path...}", s.authed(s.handlePut))
	// RemoteFiles.delete
	m.HandleFunc("DELETE "+filePrefix+"{path...}", s.authed(s.handleDelete))
	// RemoteFiles.move
	m.HandleFunc("POST /api/v1/move", s.authed(s.handleMove))
	// RemoteFiles.mkcol
	m.HandleFunc("POST /api/v1/mkcol", s.authed(s.handleMkcol))
}

// ---- middleware ----

// authed validates the Bearer token and injects the user_id. A missing/invalid
// token is 401 before any handler runs.
func (s *Server) authed(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		uid, err := s.ring.UserIDFor(r.Header.Get("Authorization"), s.now())
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, r, uid)
	}
}

func (s *Server) recover(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("panic: %s %s: %v", r.Method, r.URL.Path, rec)
				writeErr(w, http.StatusInternalServerError, "internal error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

// ---- handlers ----

func (s *Server) handleList(w http.ResponseWriter, r *http.Request, uid string) {
	dir := r.URL.Query().Get("dir")
	if dir == "" {
		dir = "/"
	}
	ents, err := s.vs.List(uid, dir)
	if err != nil {
		mapErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"entries": ents})
}

func (s *Server) handleGet(w http.ResponseWriter, r *http.Request, uid string) {
	rel := r.PathValue("path")
	data, etag, err := s.vs.Get(uid, rel)
	if err != nil {
		mapErr(w, err)
		return
	}
	if etag != "" {
		w.Header().Set("ETag", etag)
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	w.Write(data)
}

func (s *Server) handlePut(w http.ResponseWriter, r *http.Request, uid string) {
	rel := r.PathValue("path")
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.MaxBodyMB<<20))
	if err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, "body too large")
		return
	}
	mode := vault.PutUnconditional
	ifMatch := ""
	if inm := r.Header.Get("If-None-Match"); strings.TrimSpace(inm) == "*" {
		mode = vault.PutIfAbsent
	} else if im := strings.TrimSpace(r.Header.Get("If-Match")); im != "" {
		mode = vault.PutIfMatch
		ifMatch = im
	}
	etag, err := s.vs.Put(uid, rel, body, mode, ifMatch)
	if err != nil {
		mapErr(w, err)
		return
	}
	w.Header().Set("ETag", etag)
	writeJSON(w, http.StatusOK, map[string]any{"etag": etag})
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request, uid string) {
	rel := r.PathValue("path")
	if err := s.vs.Delete(uid, rel); err != nil {
		mapErr(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type moveReq struct {
	From      string `json:"from"`
	To        string `json:"to"`
	Overwrite *bool  `json:"overwrite"`
}

func (s *Server) handleMove(w http.ResponseWriter, r *http.Request, uid string) {
	var req moveReq
	if err := decodeJSON(r, &req, s.cfg.MaxBodyMB); err != nil {
		writeErr(w, http.StatusBadRequest, "bad request body")
		return
	}
	overwrite := true
	if req.Overwrite != nil {
		overwrite = *req.Overwrite
	}
	if err := s.vs.Move(uid, req.From, req.To, overwrite); err != nil {
		mapErr(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type mkcolReq struct {
	Dir string `json:"dir"`
}

func (s *Server) handleMkcol(w http.ResponseWriter, r *http.Request, uid string) {
	var req mkcolReq
	if err := decodeJSON(r, &req, s.cfg.MaxBodyMB); err != nil {
		writeErr(w, http.StatusBadRequest, "bad request body")
		return
	}
	if err := s.vs.Mkcol(uid, req.Dir); err != nil {
		mapErr(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func decodeJSON(r *http.Request, dst any, maxMB int64) error {
	if maxMB <= 0 {
		maxMB = 1
	}
	r.Body = http.MaxBytesReader(nil, r.Body, maxMB<<20)
	return json.NewDecoder(r.Body).Decode(dst)
}

func mapErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, vault.ErrNotFound):
		writeErr(w, http.StatusNotFound, "not found")
	case errors.Is(err, vault.ErrConflict), errors.Is(err, vault.ErrDestExists):
		writeErr(w, http.StatusPreconditionFailed, "precondition failed")
	case errors.Is(err, vault.ErrExists):
		writeErr(w, http.StatusConflict, "already exists")
	case errors.Is(err, vault.ErrInvalid), errors.Is(err, vault.ErrIsDir), errors.Is(err, vault.ErrUserMissing):
		writeErr(w, http.StatusBadRequest, "invalid path")
	default:
		log.Printf("internal error: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal error")
	}
}
