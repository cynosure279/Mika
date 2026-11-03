package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

const (
	serverName    = "mika-envd"
	serverVersion = "0.1.0"
)

type config struct {
	listen    string
	dataDir   string
	tokenFile string
	readOnly  bool
	maxExec   int
	maxOutput int
	tlsCert   string
	tlsKey    string
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.listen, "listen", "127.0.0.1:18787", "HTTP listen address")
	flag.StringVar(&cfg.dataDir, "data", "./data", "data directory")
	flag.StringVar(&cfg.tokenFile, "token-file", "", "file containing the bearer token (required, at least 32 characters)")
	flag.BoolVar(&cfg.readOnly, "read-only", false, "disable exec, writes, create, clone and delete")
	flag.IntVar(&cfg.maxExec, "max-exec", 4, "maximum concurrent commands")
	flag.IntVar(&cfg.maxOutput, "max-output", 1<<20, "maximum captured bytes per output stream")
	flag.StringVar(&cfg.tlsCert, "tls-cert", "", "TLS certificate (optional; otherwise terminate TLS in a reverse proxy)")
	flag.StringVar(&cfg.tlsKey, "tls-key", "", "TLS private key")
	flag.Parse()

	if cfg.tokenFile == "" {
		log.Fatal("-token-file is required")
	}
	if cfg.maxExec < 1 || cfg.maxExec > 64 {
		log.Fatal("-max-exec must be 1..64")
	}
	if cfg.maxOutput < 1024 || cfg.maxOutput > 64<<20 {
		log.Fatal("-max-output must be 1024..67108864")
	}
	token, err := readToken(cfg.tokenFile)
	if err != nil {
		log.Fatal(err)
	}
	store, err := OpenStore(cfg.dataDir, cfg.readOnly)
	if err != nil {
		log.Fatal(err)
	}
	srv := NewServer(cfg, token, store)

	httpSrv := &http.Server{
		Addr:              cfg.listen,
		Handler:           srv,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    1 << 20,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 1)
	go func() {
		if cfg.tlsCert != "" || cfg.tlsKey != "" {
			if cfg.tlsCert == "" || cfg.tlsKey == "" {
				errCh <- fmt.Errorf("both -tls-cert and -tls-key are required")
				return
			}
			errCh <- httpSrv.ListenAndServeTLS(cfg.tlsCert, cfg.tlsKey)
			return
		}
		errCh <- httpSrv.ListenAndServe()
	}()

	if cfg.tlsCert == "" && !isLoopback(cfg.listen) {
		log.Printf("warning: listening on %s without TLS; put a TLS reverse proxy in front", cfg.listen)
	}
	log.Printf("%s %s listening on %s, data %s, read-only=%v", serverName, serverVersion, cfg.listen, cfg.dataDir, cfg.readOnly)

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shutdownCtx)
	case err := <-errCh:
		if err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}
}

func readToken(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read token file: %w", err)
	}
	token := strings.TrimSpace(string(b))
	if len(token) < 32 {
		return "", fmt.Errorf("token must be at least 32 characters")
	}
	return token, nil
}

func isLoopback(addr string) bool {
	host := addr
	if i := strings.LastIndex(addr, ":"); i >= 0 {
		host = addr[:i]
	}
	host = strings.Trim(host, "[]")
	return host == "127.0.0.1" || host == "::1" || host == "localhost"
}
