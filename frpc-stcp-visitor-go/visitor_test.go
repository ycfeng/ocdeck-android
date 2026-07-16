package frpcstcpvisitor

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestParseVisitorConfigForcesLoopback(t *testing.T) {
	cfg, err := parseVisitorConfig(`{
		"name":"visitor",
		"serverName":"opencode",
		"secretKey":"secret",
		"bindAddr":"0.0.0.0",
		"bindPort":4096
	}`)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.BindAddr != "127.0.0.1" {
		t.Fatalf("unexpected bind address %q", cfg.BindAddr)
	}
}

func TestStopSessionWaitsForRunAndIsIdempotent(t *testing.T) {
	s := installTestSession(t, "wait-session")
	go func() {
		<-s.stopRequested()
		time.Sleep(30 * time.Millisecond)
		close(s.runDone)
	}()

	started := time.Now()
	results := make(chan string, 2)
	errors := make(chan error, 2)
	var callers sync.WaitGroup
	callers.Add(2)
	for range 2 {
		go func() {
			defer callers.Done()
			result, err := StopSession(s.id, 1000)
			results <- result
			errors <- err
		}()
	}
	callers.Wait()
	close(results)
	close(errors)

	if time.Since(started) < 25*time.Millisecond {
		t.Fatal("StopSession returned before the runtime completed shutdown")
	}
	for err := range errors {
		if err != nil {
			t.Fatalf("StopSession: %v", err)
		}
	}
	for resultJSON := range results {
		var result stopSessionResult
		if err := json.Unmarshal([]byte(resultJSON), &result); err != nil {
			t.Fatal(err)
		}
		if result.Phase != phaseClosed {
			t.Fatalf("unexpected stop result: %+v", result)
		}
	}
	if _, ok := getSession(s.id); ok {
		t.Fatal("closed session remained registered")
	}
}

func TestStopSessionTimeoutCanBeRetried(t *testing.T) {
	s := installTestSession(t, "timeout-session")
	if _, err := StopSession(s.id, 5); err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("expected timeout, got %v", err)
	}
	if _, ok := getSession(s.id); !ok {
		t.Fatal("timed out session was removed before shutdown completed")
	}
	close(s.runDone)
	if _, err := StopSession(s.id, 1000); err != nil {
		t.Fatalf("retry StopSession: %v", err)
	}
}

func TestSensitiveValuesAreRedacted(t *testing.T) {
	message := redactSensitive(
		"token-value secret-value",
		"token-value",
		"secret-value",
	)
	if message != "<redacted> <redacted>" {
		t.Fatalf("unexpected redaction: %q", message)
	}
}

func TestMissingSessionStateIsClosed(t *testing.T) {
	stateJSON, err := GetState("missing-session")
	if err != nil {
		t.Fatal(err)
	}
	var current state
	if err := json.Unmarshal([]byte(stateJSON), &current); err != nil {
		t.Fatal(err)
	}
	if current.Phase != phaseClosed || current.SessionID != "missing-session" {
		t.Fatalf("unexpected state: %+v", current)
	}
}

func installTestSession(t *testing.T, id string) *session {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{
		id:       id,
		cancel:   cancel,
		runDone:  make(chan struct{}),
		visitors: map[string]visitorConfig{},
	}
	sessionsMu.Lock()
	sessions[id] = s
	sessionsMu.Unlock()
	t.Cleanup(func() {
		cancel()
		sessionsMu.Lock()
		delete(sessions, id)
		sessionsMu.Unlock()
	})

	stopRequested := make(chan struct{})
	s.cancel = func() {
		cancel()
		select {
		case <-stopRequested:
		default:
			close(stopRequested)
		}
	}
	t.Cleanup(func() { <-ctx.Done() })
	testStopChannels.Store(s, stopRequested)
	return s
}

var testStopChannels sync.Map

func (s *session) stopRequested() <-chan struct{} {
	value, _ := testStopChannels.Load(s)
	return value.(chan struct{})
}
