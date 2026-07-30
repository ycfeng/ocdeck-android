package frpcstcpvisitor

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestParseSessionConfigValidatesTLSCertificatePin(t *testing.T) {
	pin := strings.Repeat("0a", sha256.Size)
	cfg, err := parseSessionConfig(`{
		"serverAddr":"frps.example.com",
		"serverPort":7000,
		"authToken":"token-value",
		"transportProtocol":"tcp",
		"wireProtocol":"v1",
		"tlsServerCertificateSha256":"` + pin + `"
	}`)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.TLSServerCertificateSHA256 != pin {
		t.Fatal("TLS certificate pin was not preserved")
	}

	for _, invalid := range []string{strings.ToUpper(pin), pin[:len(pin)-1], strings.Repeat("zz", sha256.Size)} {
		_, err := parseSessionConfig(`{
			"serverAddr":"frps.example.com",
			"serverPort":7000,
			"authToken":"token-value",
			"tlsServerCertificateSha256":"` + invalid + `"
		}`)
		if err == nil || strings.Contains(err.Error(), invalid) {
			t.Fatalf("invalid TLS certificate pin was not safely rejected")
		}
	}
}

func TestTLSConfigCustomizerValidatesExactNonCALeafAndServerName(t *testing.T) {
	leaf := newTestTLSCertificate(t, false, "frps.example.com")
	pin := sha256.Sum256(leaf.Raw)
	customizer, err := newTLSConfigCustomizer(hex.EncodeToString(pin[:]))
	if err != nil {
		t.Fatal(err)
	}
	config := &tls.Config{ServerName: "frps.example.com"}
	if err := customizer(config); err != nil {
		t.Fatal(err)
	}
	if !config.InsecureSkipVerify || config.VerifyConnection == nil {
		t.Fatal("pinned TLS verification was not installed")
	}
	if err := config.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}); err != nil {
		t.Fatalf("matching leaf was rejected: %v", err)
	}

	wrongPin := strings.Repeat("00", sha256.Size)
	wrongCustomizer, err := newTLSConfigCustomizer(wrongPin)
	if err != nil {
		t.Fatal(err)
	}
	wrongConfig := &tls.Config{ServerName: "frps.example.com"}
	if err := wrongCustomizer(wrongConfig); err != nil {
		t.Fatal(err)
	}
	if err := wrongConfig.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}); err == nil {
		t.Fatal("mismatched leaf pin was accepted")
	}

	nameConfig := &tls.Config{ServerName: "other.example.com"}
	if err := customizer(nameConfig); err != nil {
		t.Fatal(err)
	}
	if err := nameConfig.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{leaf}}); err == nil {
		t.Fatal("mismatched server name was accepted")
	}

	caLeaf := newTestTLSCertificate(t, true, "frps.example.com")
	caPin := sha256.Sum256(caLeaf.Raw)
	caCustomizer, err := newTLSConfigCustomizer(hex.EncodeToString(caPin[:]))
	if err != nil {
		t.Fatal(err)
	}
	caConfig := &tls.Config{ServerName: "frps.example.com"}
	if err := caCustomizer(caConfig); err != nil {
		t.Fatal(err)
	}
	if err := caConfig.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{caLeaf}}); err == nil {
		t.Fatal("certificate-authority leaf was accepted")
	}
}

func newTestTLSCertificate(t *testing.T, certificateAuthority bool, dnsName string) *x509.Certificate {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: dnsName},
		DNSNames:              []string{dnsName},
		NotBefore:             now.Add(-time.Minute),
		NotAfter:              now.Add(time.Hour),
		BasicConstraintsValid: true,
		IsCA:                  certificateAuthority,
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if certificateAuthority {
		template.KeyUsage |= x509.KeyUsageCertSign
	}
	encoded, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(encoded)
	if err != nil {
		t.Fatal(err)
	}
	return certificate
}

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

func TestRuntimeDiagnosticsReportOnlyAggregateCounts(t *testing.T) {
	beforeJSON, err := GetRuntimeDiagnostics()
	if err != nil {
		t.Fatal(err)
	}
	var before runtimeDiagnostics
	if err := json.Unmarshal([]byte(beforeJSON), &before); err != nil {
		t.Fatal(err)
	}
	testSession := &session{id: "diagnostic-session"}
	sessionsMu.Lock()
	sessions[testSession.id] = testSession
	sessionsMu.Unlock()
	t.Cleanup(func() {
		sessionsMu.Lock()
		delete(sessions, testSession.id)
		sessionsMu.Unlock()
	})

	afterJSON, err := GetRuntimeDiagnostics()
	if err != nil {
		t.Fatal(err)
	}
	var after runtimeDiagnostics
	if err := json.Unmarshal([]byte(afterJSON), &after); err != nil {
		t.Fatal(err)
	}
	if after.SessionCount != before.SessionCount+1 || after.GoroutineCount <= 0 {
		t.Fatalf("unexpected aggregate diagnostics: %+v", after)
	}
	if len(after.OwnerCounts) == 0 || after.OwnerCounts["client.controls"] < 0 {
		t.Fatalf("unexpected owner diagnostics: %+v", after.OwnerCounts)
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
