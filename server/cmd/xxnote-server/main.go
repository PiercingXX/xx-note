// xxnote-server is the fabric backend for xx-note: a per-user notes server that
// stores each user's notes as plain .md files under
// <data>/users/<user_id>/vault/, authenticates every request against the estate
// fabric identity (a ClusterKeyring v1 bearer token minted by xx-chat's
// POST /api/v1/fabric/login), and derives the on-disk path from the validated
// token's user_id ONLY — never from any client-supplied field.
//
// Single static binary, stdlib only, modeled on xx-drive.
package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"time"

	"xxnote-server/internal/api"
	"xxnote-server/internal/fabric"
	"xxnote-server/internal/vault"
)

func main() {
	var (
		addr    = flag.String("addr", envOr("XXNOTE_ADDR", "127.0.0.1:8746"), "listen address")
		dataDir = flag.String("data", envOr("XXNOTE_DATA_DIR", "/srv/deep/xxnote"), "data directory (ZFS pool at deploy)")
		ringPth = flag.String("keyring", os.Getenv(fabric.EnvKeyringPath), "fabric cluster keyring path (or "+fabric.EnvKeyringPath+")")
	)
	flag.Parse()
	log.SetFlags(log.LstdFlags | log.LUTC)

	// Fail closed at boot if the validate keyring is missing — never serve
	// requests we cannot authenticate.
	ring, err := fabric.LoadKeyring(*ringPth)
	if err != nil {
		log.Fatalf("fabric keyring: %v", err)
	}

	vs, err := vault.New(*dataDir)
	if err != nil {
		log.Fatalf("init vault store: %v", err)
	}

	srv := api.New(api.Config{Addr: *addr}, ring, vs)
	httpSrv := &http.Server{
		Addr:              *addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 15 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("xxnote-server listening on %s (data: %s)", *addr, *dataDir)
	log.Fatal(httpSrv.ListenAndServe())
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
