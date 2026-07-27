package frpcstcpvisitor

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"opencode-frpc-stcp-visitor/internal/contractfixture"
)

const (
	bridgeDTOContractPath = "../frpc-stcp-visitor/src/test/resources/io/github/ycfeng/ocdeck/frpcstcpvisitor/contract/v1/bridge/dto-contract.json"
	maxBridgeDTOContract  = 1 << 20
)

type bridgeDTOFixture struct {
	SessionConfig                   json.RawMessage `json:"sessionConfig"`
	SessionConfigDefaults           json.RawMessage `json:"sessionConfigDefaults"`
	VisitorConfig                   json.RawMessage `json:"visitorConfig"`
	VisitorConfigDefaults           json.RawMessage `json:"visitorConfigDefaults"`
	EnsureVisitorResult             json.RawMessage `json:"ensureVisitorResult"`
	VisitorReadyResult              json.RawMessage `json:"visitorReadyResult"`
	VisitorReadyResultWithoutError  json.RawMessage `json:"visitorReadyResultWithoutError"`
	VisitorRuntimeState             json.RawMessage `json:"visitorRuntimeState"`
	VisitorRuntimeStateWithoutError json.RawMessage `json:"visitorRuntimeStateWithoutError"`
	State                           json.RawMessage `json:"state"`
	StateWithoutOptionalFields      json.RawMessage `json:"stateWithoutOptionalFields"`
	StopSessionResult               json.RawMessage `json:"stopSessionResult"`
}

func TestCanonicalContractFixtures(t *testing.T) {
	result, err := contractfixture.Check()
	if err != nil {
		t.Fatal(err)
	}
	if filepath.IsAbs(result.Directory) {
		t.Fatal("contract fixture command exposed an absolute local path")
	}
}

func TestCanonicalBridgeDTOContractMatchesGoTypes(t *testing.T) {
	data := readBoundedTestFile(t, bridgeDTOContractPath, maxBridgeDTOContract)
	var fixture bridgeDTOFixture
	decodeStrictTestJSON(t, data, &fixture)

	session, err := parseSessionConfig(string(fixture.SessionConfig))
	if err != nil {
		t.Fatal(err)
	}
	assertGoBridgeDTO(t, fixture.SessionConfig, session, sessionConfig{
		ServerAddr:        "bridge-frps.invalid",
		ServerPort:        7000,
		AuthToken:         redactedValue,
		User:              "bridge-user",
		TransportProtocol: "tcp",
		WireProtocol:      "v2",
	})
	defaultSession, err := parseSessionConfig(string(fixture.SessionConfigDefaults))
	if err != nil {
		t.Fatal(err)
	}
	assertGoBridgeValue(t, defaultSession, sessionConfig{
		ServerAddr:        "bridge-defaults-frps.invalid",
		ServerPort:        7000,
		AuthToken:         redactedValue,
		TransportProtocol: "tcp",
		WireProtocol:      "v1",
	})

	visitor, err := parseVisitorConfig(string(fixture.VisitorConfig))
	if err != nil {
		t.Fatal(err)
	}
	assertGoBridgeDTO(t, fixture.VisitorConfig, visitor, visitorConfig{
		Name:           "bridge-visitor",
		ServerName:     "bridge-server",
		ServerUser:     "bridge-server-user",
		SecretKey:      redactedValue,
		BindAddr:       "127.0.0.1",
		BindPort:       4096,
		UseEncryption:  false,
		UseCompression: false,
	})
	defaultVisitor, err := parseVisitorConfig(string(fixture.VisitorConfigDefaults))
	if err != nil {
		t.Fatal(err)
	}
	assertGoBridgeValue(t, defaultVisitor, visitorConfig{
		Name:           "bridge-defaults-visitor",
		ServerName:     "bridge-defaults-server",
		SecretKey:      redactedValue,
		BindAddr:       "127.0.0.1",
		BindPort:       4096,
		UseEncryption:  false,
		UseCompression: false,
	})

	const (
		desiredRevision = uint64(4_294_967_297)
		configRevision  = uint64(4_294_967_299)
		controlEpoch    = uint64(8_589_934_593)
	)
	runtimeState := visitorRuntimeState{
		DesiredRevision:   desiredRevision,
		Phase:             "ready",
		ListenerBound:     true,
		BoundControlEpoch: controlEpoch,
		LastError:         redactedValue,
	}

	var ensured ensureVisitorResult
	decodeStrictTestJSON(t, fixture.EnsureVisitorResult, &ensured)
	assertGoBridgeDTO(t, fixture.EnsureVisitorResult, ensured, ensureVisitorResult{
		BindPort:        4096,
		DesiredRevision: desiredRevision,
	})

	var ready visitorReadyResult
	decodeStrictTestJSON(t, fixture.VisitorReadyResult, &ready)
	assertGoBridgeDTO(t, fixture.VisitorReadyResult, ready, visitorReadyResult{
		Name:              "bridge-visitor",
		DesiredRevision:   desiredRevision,
		Phase:             "ready",
		ListenerBound:     true,
		BoundControlEpoch: controlEpoch,
		LastError:         redactedValue,
	})
	var readyWithoutError visitorReadyResult
	decodeStrictTestJSON(t, fixture.VisitorReadyResultWithoutError, &readyWithoutError)
	assertGoBridgeDTO(t, fixture.VisitorReadyResultWithoutError, readyWithoutError, visitorReadyResult{
		Name:              "bridge-defaults-visitor",
		DesiredRevision:   desiredRevision,
		Phase:             "ready",
		ListenerBound:     true,
		BoundControlEpoch: controlEpoch,
	})

	var runtime visitorRuntimeState
	decodeStrictTestJSON(t, fixture.VisitorRuntimeState, &runtime)
	assertGoBridgeDTO(t, fixture.VisitorRuntimeState, runtime, runtimeState)
	var runtimeWithoutError visitorRuntimeState
	decodeStrictTestJSON(t, fixture.VisitorRuntimeStateWithoutError, &runtimeWithoutError)
	assertGoBridgeDTO(t, fixture.VisitorRuntimeStateWithoutError, runtimeWithoutError, visitorRuntimeState{
		DesiredRevision:   desiredRevision,
		Phase:             "pending",
		ListenerBound:     false,
		BoundControlEpoch: 0,
	})

	var current state
	decodeStrictTestJSON(t, fixture.State, &current)
	assertGoBridgeDTO(t, fixture.State, current, state{
		SessionID:      "bridge-session",
		Phase:          "open",
		ConfigRevision: configRevision,
		ControlEpoch:   controlEpoch,
		LastError:      redactedValue,
		Visitors: map[string]visitorRuntimeState{
			"bridge-visitor": runtimeState,
		},
	})
	var currentWithoutOptionalFields state
	decodeStrictTestJSON(t, fixture.StateWithoutOptionalFields, &currentWithoutOptionalFields)
	assertGoBridgeDTO(t, fixture.StateWithoutOptionalFields, currentWithoutOptionalFields, state{
		SessionID:      "bridge-defaults-session",
		Phase:          "closed",
		ConfigRevision: 0,
		ControlEpoch:   0,
	})

	var stopped stopSessionResult
	decodeStrictTestJSON(t, fixture.StopSessionResult, &stopped)
	assertGoBridgeDTO(t, fixture.StopSessionResult, stopped, stopSessionResult{
		SessionID: "bridge-session",
		Phase:     "closed",
	})
}

func readBoundedTestFile(t *testing.T, path string, maximum int64) []byte {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	data, readErr := io.ReadAll(io.LimitReader(file, maximum+1))
	closeErr := file.Close()
	if readErr != nil {
		t.Fatal(readErr)
	}
	if closeErr != nil {
		t.Fatal(closeErr)
	}
	if int64(len(data)) > maximum {
		t.Fatal("bridge DTO contract exceeded its test limit")
	}
	return data
}

func decodeStrictTestJSON(t *testing.T, data []byte, target any) {
	t.Helper()
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		t.Fatal(err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		t.Fatal("bridge DTO contract contains trailing JSON")
	}
}

func assertGoBridgeDTO(t *testing.T, fixture json.RawMessage, actual, expected any) {
	t.Helper()
	assertGoBridgeValue(t, actual, expected)
	encoded, err := marshalJSON(actual)
	if err != nil {
		t.Fatal(err)
	}
	var fixtureValue any
	var encodedValue any
	if err := json.Unmarshal(fixture, &fixtureValue); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(encoded), &encodedValue); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(fixtureValue, encodedValue) {
		t.Fatal("Go bridge DTO JSON semantics changed")
	}
}

func assertGoBridgeValue(t *testing.T, actual, expected any) {
	t.Helper()
	if !reflect.DeepEqual(expected, actual) {
		t.Fatalf("bridge DTO value changed: expected %#v, got %#v", expected, actual)
	}
}
