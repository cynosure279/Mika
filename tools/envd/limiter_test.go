package main

import "testing"

func TestLimiterBurst(t *testing.T) {
	l := newLimiter(4, 0)
	if !l.allow("a") {
		t.Fatal("first request must be allowed")
	}
	if l.allow("a") {
		t.Fatal("second request within the same instant must be limited")
	}
	if !l.allow("b") {
		t.Fatal("another caller has its own bucket")
	}
}

func TestLimiterAuthCooldown(t *testing.T) {
	l := newLimiter(0, 2)
	l.authFailed("a")
	if !l.allow("a") {
		t.Fatal("one failure must not block yet")
	}
	l.authFailed("a")
	if l.allow("a") {
		t.Fatal("second failure must start the cooldown")
	}
	if !l.allow("b") {
		t.Fatal("cooldown must be per caller")
	}
}
