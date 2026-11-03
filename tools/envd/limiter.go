package main

import (
	"math"
	"sync"
	"time"
)

// limiter is a small per-caller token bucket plus an authentication-failure
// cooldown. It is not a replacement for a reverse proxy, but keeps a single
// caller from flooding envd or guessing tokens indefinitely.
type limiter struct {
	mu            sync.Mutex
	ratePerSec    float64
	burst         float64
	authFailLimit int
	entries       map[string]*clientBucket
}

type clientBucket struct {
	tokens       float64
	last         time.Time
	seen         time.Time
	blockedUntil time.Time
	fails        int
}

func newLimiter(ratePerMin, authFailLimit int) *limiter {
	if ratePerMin <= 0 && authFailLimit <= 0 {
		return nil
	}
	l := &limiter{
		ratePerSec:    float64(ratePerMin) / 60.0,
		burst:         math.Max(1, float64(ratePerMin)/4),
		authFailLimit: authFailLimit,
		entries:       map[string]*clientBucket{},
	}
	if ratePerMin <= 0 {
		l.ratePerSec = 0
		l.burst = 0
	}
	return l
}

func (l *limiter) allow(key string) bool {
	if l == nil {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	b := l.bucketLocked(key, now)
	if !b.blockedUntil.IsZero() && now.Before(b.blockedUntil) {
		return false
	}
	if l.ratePerSec > 0 {
		elapsed := now.Sub(b.last).Seconds()
		b.tokens = math.Min(l.burst, b.tokens+elapsed*l.ratePerSec)
		b.last = now
		if b.tokens < 1 {
			return false
		}
		b.tokens--
	}
	b.seen = now
	l.pruneLocked(now)
	return true
}

func (l *limiter) authFailed(key string) {
	if l == nil || l.authFailLimit <= 0 {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	b := l.bucketLocked(key, now)
	b.fails++
	if b.fails >= l.authFailLimit {
		b.blockedUntil = now.Add(time.Minute)
		b.fails = 0
	}
	b.seen = now
}

func (l *limiter) authOK(key string) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if b := l.entries[key]; b != nil {
		b.fails = 0
	}
}

func (l *limiter) bucketLocked(key string, now time.Time) *clientBucket {
	b := l.entries[key]
	if b == nil {
		b = &clientBucket{tokens: l.burst, last: now, seen: now}
		l.entries[key] = b
	}
	return b
}

func (l *limiter) pruneLocked(now time.Time) {
	if len(l.entries) <= 1024 {
		return
	}
	for key, b := range l.entries {
		if now.Sub(b.seen) > 10*time.Minute {
			delete(l.entries, key)
		}
	}
}
