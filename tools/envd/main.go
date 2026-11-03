package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"time"
)

const (
	serverName    = "mika-envd"
	serverVersion = "0.1.0"
)

type config struct {
	listen     string
	dataDir    string
	tokenFile  string
	tokenEnv   string
	readOnly   bool
	maxExec    int
	maxOutput  int
	execUser   int
	execGroup  int
	rateLimit  int
	authLimit  int
	trustProxy bool
	publicURL  string
	maxUpload  int64
	allowRoots []string
	tlsCert    string
	tlsKey     string
}

type stringList []string

func (s *stringList) String() string { return strings.Join(*s, ",") }

func (s *stringList) Set(value string) error {
	*s = append(*s, value)
	return nil
}

func main() {
	cfg := config{}
	flag.StringVar(&cfg.listen, "listen", "127.0.0.1:18787", "HTTP listen address")
	flag.StringVar(&cfg.dataDir, "data", "./data", "data directory")
	flag.StringVar(&cfg.tokenFile, "token-file", "", "file containing the bearer token (at least 32 characters)")
	flag.StringVar(&cfg.tokenEnv, "token-env", "", "environment variable containing the bearer token; preferred with -exec-user")
	flag.BoolVar(&cfg.readOnly, "read-only", false, "disable exec, writes, create, clone and delete")
	flag.IntVar(&cfg.maxExec, "max-exec", 4, "maximum concurrent commands")
	flag.IntVar(&cfg.maxOutput, "max-output", 1<<20, "maximum captured bytes per output stream")
	flag.IntVar(&cfg.execUser, "exec-user", -1, "run commands as this numeric uid (Unix only; requires privileges)")
	flag.IntVar(&cfg.execGroup, "exec-group", -1, "group id for -exec-user (default: same as uid)")
	flag.IntVar(&cfg.rateLimit, "rate-limit", 120, "requests per minute per caller (0 disables)")
	flag.IntVar(&cfg.authLimit, "auth-limit", 5, "failed authentications per minute before a 60s block (0 disables)")
	flag.BoolVar(&cfg.trustProxy, "trust-proxy", false, "use the last X-Forwarded-For address as the caller")
	flag.StringVar(&cfg.publicURL, "public-url", "", "base URL for signed file links, e.g. https://env.example.com")
	flag.Int64Var(&cfg.maxUpload, "max-upload", 1<<30, "maximum upload bytes (default 1 GiB)")
	flag.Var((*stringList)(&cfg.allowRoots), "allow-root", "existing directory that env_create may attach with path (repeatable)")
	flag.StringVar(&cfg.tlsCert, "tls-cert", "", "TLS certificate (optional; otherwise terminate TLS in a reverse proxy)")
	flag.StringVar(&cfg.tlsKey, "tls-key", "", "TLS private key")
	flag.Parse()

	if cfg.tokenFile == "" && cfg.tokenEnv == "" {
		log.Fatal("-token-file or -token-env is required")
	}
	if cfg.maxExec < 1 || cfg.maxExec > 64 {
		log.Fatal("-max-exec must be 1..64")
	}
	if cfg.maxOutput < 1024 || cfg.maxOutput > 64<<20 {
		log.Fatal("-max-output must be 1024..67108864")
	}
	if cfg.rateLimit < 0 || cfg.authLimit < 0 {
		log.Fatal("-rate-limit and -auth-limit must be >= 0")
	}
	if cfg.maxUpload <= 0 || cfg.maxUpload > 8<<30 {
		log.Fatal("-max-upload must be 1..8589934592")
	}
	if cfg.publicURL != "" && !strings.HasPrefix(cfg.publicURL, "http://") && !strings.HasPrefix(cfg.publicURL, "https://") {
		log.Fatal("-public-url must start with http:// or https://")
	}
	if cfg.execUser >= 0 && cfg.execGroup < 0 {
		cfg.execGroup = cfg.execUser
	}
	if cfg.execUser < 0 && cfg.execGroup >= 0 {
		log.Fatal("-exec-group requires -exec-user")
	}
	var token string
	if cfg.tokenEnv != "" {
		token = strings.TrimSpace(os.Getenv(cfg.tokenEnv))
		if len(token) < 32 {
			log.Fatalf("%s must contain a token of at least 32 characters", cfg.tokenEnv)
		}
	} else {
		var err error
		token, err = readToken(cfg.tokenFile)
		if err != nil {
			log.Fatal(err)
		}
	}
	store, err := OpenStore(cfg.dataDir, cfg.readOnly, cfg.allowRoots...)
	if err != nil {
		log.Fatal(err)
	}
	if err := validateExecUser(cfg.execUser, cfg.execGroup); err != nil {
		log.Fatal(err)
	}
	if cfg.execUser >= 0 {
		setGroupUmask()
	}
	if err := store.ConfigureExecUser(cfg.execUser, cfg.execGroup); err != nil {
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

	ctx, stop := signal.NotifyContext(context.Background(), shutdownSignals()...)
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
	if fi, err := os.Stat(path); err == nil && runtime.GOOS != "windows" && fi.Mode().Perm()&0o077 != 0 {
		log.Printf("warning: token file %s is readable by group/others; chmod 600 it", path)
	}
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
