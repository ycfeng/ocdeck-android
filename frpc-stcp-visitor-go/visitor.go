package frpcstcpvisitor

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
)

const (
	phaseClosed       = "closed"
	redactedValue     = "<redacted>"
	defaultStopWait   = 10 * time.Second
	maxNativeWait     = 2 * time.Minute
	minimumNativeWait = time.Millisecond
)

var (
	sessionsMu sync.Mutex
	sessions   = map[string]*session{}
)

type session struct {
	id        string
	common    *v1.ClientCommonConfig
	service   *client.Service
	cancel    context.CancelFunc
	runDone   chan struct{}
	stopOnce  sync.Once
	authToken string

	mu       sync.Mutex
	visitors map[string]visitorConfig
	runErr   error
}

// StartSession creates a frpc runtime. JSON keeps the GoMobile API independent
// from frp's internal types and prevents generated Java value objects from
// exposing authentication fields.
func StartSession(configJSON string) (string, error) {
	cfg, err := parseSessionConfig(configJSON)
	if err != nil {
		return "", err
	}

	cfgSource := source.NewConfigSource()
	aggregator := source.NewAggregator(cfgSource)
	loginFailExit := true
	common := &v1.ClientCommonConfig{
		Auth: v1.AuthClientConfig{
			Method: v1.AuthMethodToken,
			AdditionalScopes: []v1.AuthScope{
				v1.AuthScopeHeartBeats,
				v1.AuthScopeNewWorkConns,
			},
			Token: cfg.AuthToken,
		},
		User:          cfg.User,
		ServerAddr:    cfg.ServerAddr,
		ServerPort:    cfg.ServerPort,
		LoginFailExit: &loginFailExit,
		Log: v1.LogConfig{
			To:                "console",
			Level:             "error",
			MaxDays:           1,
			DisablePrintColor: true,
		},
		Transport: v1.ClientTransportConfig{
			Protocol:     cfg.TransportProtocol,
			WireProtocol: cfg.WireProtocol,
		},
	}

	service, err := client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: aggregator,
	})
	if err != nil {
		return "", redactedError(err, cfg.AuthToken)
	}

	sessionID := newSessionID()
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{
		id:        sessionID,
		common:    common,
		service:   service,
		cancel:    cancel,
		runDone:   make(chan struct{}),
		visitors:  map[string]visitorConfig{},
		authToken: cfg.AuthToken,
	}

	sessionsMu.Lock()
	sessions[sessionID] = s
	sessionsMu.Unlock()

	go func() {
		runErr := service.Run(ctx)
		s.mu.Lock()
		s.runErr = runErr
		close(s.runDone)
		s.mu.Unlock()
	}()

	return sessionID, nil
}

// EnsureVisitor accepts the desired STCP visitor configuration. Readiness is
// reported separately by WaitVisitorReady after frp owns the real listener.
func EnsureVisitor(sessionID string, visitorJSON string) (string, error) {
	cfg, err := parseVisitorConfig(visitorJSON)
	if err != nil {
		return "", err
	}
	s, ok := getSession(sessionID)
	if !ok {
		return "", fmt.Errorf("frpc session %s is not found", sessionID)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.finishedErrorLocked(); err != nil {
		return "", err
	}

	nextVisitors := cloneVisitors(s.visitors)
	nextVisitors[cfg.Name] = cfg
	if err := validateUniqueBindPorts(nextVisitors); err != nil {
		return "", err
	}

	_, updateErr := s.service.UpdateConfigSourceWithRevision(
		s.common,
		nil,
		buildVisitorConfigurers(nextVisitors),
	)
	runtimeState, accepted := s.service.GetVisitorRuntimeState(cfg.Name)
	if !accepted {
		if updateErr != nil {
			return "", redactedError(updateErr, s.sensitiveValuesLocked(cfg.SecretKey)...)
		}
		return "", fmt.Errorf("frpc visitor %s was not accepted", cfg.Name)
	}

	// A synchronous bind failure is represented in runtime state and may be
	// retried by frp. The desired configuration itself has already been accepted.
	s.visitors = nextVisitors
	return marshalJSON(ensureVisitorResult{
		BindPort:        cfg.BindPort,
		DesiredRevision: runtimeState.DesiredRevision,
	})
}

// WaitVisitorReady blocks until the exact desired revision is bound by the
// current open control epoch, or until timeoutMillis elapses.
func WaitVisitorReady(
	sessionID string,
	visitorName string,
	desiredRevision int64,
	timeoutMillis int64,
) (string, error) {
	visitorName = strings.TrimSpace(visitorName)
	if visitorName == "" {
		return "", errors.New("visitorName is required")
	}
	if desiredRevision <= 0 {
		return "", errors.New("desiredRevision must be positive")
	}
	timeout, err := nativeWaitDuration(timeoutMillis)
	if err != nil {
		return "", err
	}
	s, ok := getSession(sessionID)
	if !ok {
		return "", fmt.Errorf("frpc session %s is not found", sessionID)
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	runtimeState, err := s.service.WaitVisitorReady(ctx, visitorName, uint64(desiredRevision))
	if err != nil {
		return "", redactedError(err, s.sensitiveValues()...)
	}
	return marshalJSON(visitorReadyResult{
		Name:              visitorName,
		DesiredRevision:   runtimeState.DesiredRevision,
		Phase:             runtimeState.Phase,
		ListenerBound:     runtimeState.ListenerBound,
		BoundControlEpoch: runtimeState.BoundControlEpoch,
		LastError:         redactSensitive(runtimeState.LastError, s.sensitiveValues()...),
	})
}

func StopVisitor(sessionID string, visitorName string) error {
	visitorName = strings.TrimSpace(visitorName)
	if visitorName == "" {
		return errors.New("visitorName is required")
	}
	s, ok := getSession(sessionID)
	if !ok {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.visitors[visitorName]; !ok {
		return nil
	}

	nextVisitors := cloneVisitors(s.visitors)
	delete(nextVisitors, visitorName)
	_, updateErr := s.service.UpdateConfigSourceWithRevision(
		s.common,
		nil,
		buildVisitorConfigurers(nextVisitors),
	)
	_, stillDesired := s.service.GetVisitorRuntimeState(visitorName)
	if updateErr != nil && stillDesired {
		return redactedError(updateErr, s.sensitiveValuesLocked()...)
	}
	s.visitors = nextVisitors
	return nil
}

// StopSession waits for Service.Run to return, which means the control and
// visitor listeners have completed shutdown. Calls are idempotent.
func StopSession(sessionID string, timeoutMillis int64) (string, error) {
	timeout := defaultStopWait
	if timeoutMillis > 0 {
		var err error
		timeout, err = nativeWaitDuration(timeoutMillis)
		if err != nil {
			return "", err
		}
	}
	s, ok := getSession(sessionID)
	if !ok {
		return marshalJSON(stopSessionResult{SessionID: sessionID, Phase: phaseClosed})
	}

	s.stopOnce.Do(s.cancel)
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-s.runDone:
		sessionsMu.Lock()
		if sessions[sessionID] == s {
			delete(sessions, sessionID)
		}
		sessionsMu.Unlock()
		return marshalJSON(stopSessionResult{SessionID: sessionID, Phase: phaseClosed})
	case <-timer.C:
		return "", fmt.Errorf("timed out waiting for frpc session %s to stop", sessionID)
	}
}

func GetState(sessionID string) (string, error) {
	s, ok := getSession(sessionID)
	if !ok {
		return marshalJSON(state{SessionID: sessionID, Phase: phaseClosed})
	}
	runtimeState := s.service.RuntimeState()
	values := s.sensitiveValues()
	visitors := make(map[string]visitorRuntimeState, len(runtimeState.Visitors))
	for name, visitorState := range runtimeState.Visitors {
		visitors[name] = visitorRuntimeState{
			DesiredRevision:   visitorState.DesiredRevision,
			Phase:             visitorState.Phase,
			ListenerBound:     visitorState.ListenerBound,
			BoundControlEpoch: visitorState.BoundControlEpoch,
			LastError:         redactSensitive(visitorState.LastError, values...),
		}
	}
	lastError := runtimeState.LastError
	s.mu.Lock()
	if lastError == "" && s.runErr != nil {
		lastError = s.runErr.Error()
	}
	s.mu.Unlock()
	return marshalJSON(state{
		SessionID:      sessionID,
		Phase:          runtimeState.Phase,
		ConfigRevision: runtimeState.ConfigRevision,
		ControlEpoch:   runtimeState.ControlEpoch,
		LastError:      redactSensitive(lastError, values...),
		Visitors:       visitors,
	})
}

func parseSessionConfig(configJSON string) (sessionConfig, error) {
	var cfg sessionConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return cfg, fmt.Errorf("invalid session config JSON: %w", err)
	}
	cfg.ServerAddr = strings.TrimSpace(cfg.ServerAddr)
	cfg.User = strings.TrimSpace(cfg.User)
	cfg.TransportProtocol = defaultString(strings.TrimSpace(cfg.TransportProtocol), "tcp")
	cfg.WireProtocol = defaultString(strings.TrimSpace(cfg.WireProtocol), "v1")
	if cfg.ServerAddr == "" {
		return cfg, errors.New("serverAddr is required")
	}
	if !isValidPort(cfg.ServerPort) {
		return cfg, errors.New("serverPort must be between 1 and 65535")
	}
	if !isValidTransportProtocol(cfg.TransportProtocol) {
		return cfg, fmt.Errorf("unsupported transportProtocol %q", cfg.TransportProtocol)
	}
	if cfg.WireProtocol != "v1" && cfg.WireProtocol != "v2" {
		return cfg, fmt.Errorf("unsupported wireProtocol %q", cfg.WireProtocol)
	}
	return cfg, nil
}

func parseVisitorConfig(visitorJSON string) (visitorConfig, error) {
	var cfg visitorConfig
	if err := json.Unmarshal([]byte(visitorJSON), &cfg); err != nil {
		return cfg, fmt.Errorf("invalid visitor config JSON: %w", err)
	}
	cfg.Name = strings.TrimSpace(cfg.Name)
	cfg.ServerName = strings.TrimSpace(cfg.ServerName)
	cfg.ServerUser = strings.TrimSpace(cfg.ServerUser)
	cfg.SecretKey = strings.TrimSpace(cfg.SecretKey)
	cfg.BindAddr = "127.0.0.1"
	if cfg.Name == "" {
		return cfg, errors.New("visitor name is required")
	}
	if cfg.ServerName == "" {
		return cfg, errors.New("serverName is required")
	}
	if cfg.SecretKey == "" {
		return cfg, errors.New("secretKey is required")
	}
	if !isValidPort(cfg.BindPort) {
		return cfg, errors.New("bindPort must be between 1 and 65535")
	}
	return cfg, nil
}

func buildVisitorConfigurers(visitors map[string]visitorConfig) []v1.VisitorConfigurer {
	names := make([]string, 0, len(visitors))
	for name := range visitors {
		names = append(names, name)
	}
	sort.Strings(names)

	cfgs := make([]v1.VisitorConfigurer, 0, len(names))
	for _, name := range names {
		visitor := visitors[name]
		cfgs = append(cfgs, &v1.STCPVisitorConfig{
			VisitorBaseConfig: v1.VisitorBaseConfig{
				Name:       visitor.Name,
				Type:       string(v1.VisitorTypeSTCP),
				SecretKey:  visitor.SecretKey,
				ServerUser: visitor.ServerUser,
				ServerName: visitor.ServerName,
				BindAddr:   visitor.BindAddr,
				BindPort:   visitor.BindPort,
				Transport: v1.VisitorTransport{
					UseEncryption:  visitor.UseEncryption,
					UseCompression: visitor.UseCompression,
				},
			},
		})
	}
	return cfgs
}

func validateUniqueBindPorts(visitors map[string]visitorConfig) error {
	used := map[int]string{}
	for _, visitor := range visitors {
		if existing, ok := used[visitor.BindPort]; ok {
			return fmt.Errorf("bindPort %d is already used by visitor %s", visitor.BindPort, existing)
		}
		used[visitor.BindPort] = visitor.Name
	}
	return nil
}

func cloneVisitors(visitors map[string]visitorConfig) map[string]visitorConfig {
	clone := make(map[string]visitorConfig, len(visitors))
	for name, visitor := range visitors {
		clone[name] = visitor
	}
	return clone
}

func getSession(sessionID string) (*session, bool) {
	sessionsMu.Lock()
	defer sessionsMu.Unlock()
	s, ok := sessions[sessionID]
	return s, ok
}

func (s *session) finishedErrorLocked() error {
	select {
	case <-s.runDone:
		if s.runErr != nil {
			return redactedError(s.runErr, s.sensitiveValuesLocked()...)
		}
		return fmt.Errorf("frpc session %s is closed", s.id)
	default:
		return nil
	}
}

func (s *session) sensitiveValues(extra ...string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sensitiveValuesLocked(extra...)
}

func (s *session) sensitiveValuesLocked(extra ...string) []string {
	values := make([]string, 0, 1+len(s.visitors)+len(extra))
	values = append(values, s.authToken)
	for _, visitor := range s.visitors {
		values = append(values, visitor.SecretKey)
	}
	values = append(values, extra...)
	return values
}

func nativeWaitDuration(timeoutMillis int64) (time.Duration, error) {
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout < minimumNativeWait || timeout > maxNativeWait {
		return 0, fmt.Errorf("timeoutMillis must be between 1 and %d", maxNativeWait.Milliseconds())
	}
	return timeout, nil
}

func marshalJSON(value any) (string, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func redactedError(err error, values ...string) error {
	if err == nil {
		return nil
	}
	return errors.New(redactSensitive(err.Error(), values...))
}

func redactSensitive(message string, values ...string) string {
	redacted := message
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		redacted = strings.ReplaceAll(redacted, value, redactedValue)
	}
	return redacted
}

func newSessionID() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err == nil {
		return hex.EncodeToString(bytes[:])
	}
	return strconv.FormatInt(time.Now().UnixNano(), 36)
}

func defaultString(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func isValidPort(port int) bool {
	return port > 0 && port <= 65535
}

func isValidTransportProtocol(protocol string) bool {
	switch protocol {
	case "tcp", "kcp", "quic", "websocket", "wss":
		return true
	default:
		return false
	}
}
