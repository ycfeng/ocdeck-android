package contractfixture

type bridgeDTOContract struct {
	SessionConfig                   bridgeSessionConfig                   `json:"sessionConfig"`
	SessionConfigDefaults           bridgeSessionConfigDefaults           `json:"sessionConfigDefaults"`
	VisitorConfig                   bridgeVisitorConfig                   `json:"visitorConfig"`
	VisitorConfigDefaults           bridgeVisitorConfigDefaults           `json:"visitorConfigDefaults"`
	EnsureVisitorResult             bridgeEnsureVisitorResult             `json:"ensureVisitorResult"`
	VisitorReadyResult              bridgeVisitorReadyResult              `json:"visitorReadyResult"`
	VisitorReadyResultWithoutError  bridgeVisitorReadyResultWithoutError  `json:"visitorReadyResultWithoutError"`
	VisitorRuntimeState             bridgeVisitorRuntimeState             `json:"visitorRuntimeState"`
	VisitorRuntimeStateWithoutError bridgeVisitorRuntimeStateWithoutError `json:"visitorRuntimeStateWithoutError"`
	State                           bridgeState                           `json:"state"`
	StateWithoutOptionalFields      bridgeStateWithoutOptionalFields      `json:"stateWithoutOptionalFields"`
	StopSessionResult               bridgeStopSessionResult               `json:"stopSessionResult"`
}

type bridgeSessionConfig struct {
	ServerAddr        string `json:"serverAddr"`
	ServerPort        int    `json:"serverPort"`
	AuthToken         string `json:"authToken"`
	User              string `json:"user"`
	TransportProtocol string `json:"transportProtocol"`
	WireProtocol      string `json:"wireProtocol"`
}

type bridgeSessionConfigDefaults struct {
	ServerAddr string `json:"serverAddr"`
	ServerPort int    `json:"serverPort"`
	AuthToken  string `json:"authToken"`
}

type bridgeVisitorConfig struct {
	Name           string `json:"name"`
	ServerName     string `json:"serverName"`
	ServerUser     string `json:"serverUser"`
	SecretKey      string `json:"secretKey"`
	BindAddr       string `json:"bindAddr"`
	BindPort       int    `json:"bindPort"`
	UseEncryption  bool   `json:"useEncryption"`
	UseCompression bool   `json:"useCompression"`
}

type bridgeVisitorConfigDefaults struct {
	Name       string `json:"name"`
	ServerName string `json:"serverName"`
	SecretKey  string `json:"secretKey"`
	BindPort   int    `json:"bindPort"`
}

type bridgeEnsureVisitorResult struct {
	BindPort        int    `json:"bindPort"`
	DesiredRevision uint64 `json:"desiredRevision"`
}

type bridgeVisitorReadyResult struct {
	Name              string `json:"name"`
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
	LastError         string `json:"lastError"`
}

type bridgeVisitorReadyResultWithoutError struct {
	Name              string `json:"name"`
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
}

type bridgeVisitorRuntimeState struct {
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
	LastError         string `json:"lastError"`
}

type bridgeVisitorRuntimeStateWithoutError struct {
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
}

type bridgeState struct {
	SessionID      string                               `json:"sessionId"`
	Phase          string                               `json:"phase"`
	ConfigRevision uint64                               `json:"configRevision"`
	ControlEpoch   uint64                               `json:"controlEpoch"`
	LastError      string                               `json:"lastError"`
	Visitors       map[string]bridgeVisitorRuntimeState `json:"visitors"`
}

type bridgeStateWithoutOptionalFields struct {
	SessionID      string `json:"sessionId"`
	Phase          string `json:"phase"`
	ConfigRevision uint64 `json:"configRevision"`
	ControlEpoch   uint64 `json:"controlEpoch"`
}

type bridgeStopSessionResult struct {
	SessionID string `json:"sessionId"`
	Phase     string `json:"phase"`
}

func buildBridgeDTOContract() ([]byte, error) {
	const (
		desiredRevision = uint64(4_294_967_297)
		configRevision  = uint64(4_294_967_299)
		controlEpoch    = uint64(8_589_934_593)
	)
	runtimeState := bridgeVisitorRuntimeState{
		DesiredRevision:   desiredRevision,
		Phase:             "ready",
		ListenerBound:     true,
		BoundControlEpoch: controlEpoch,
		LastError:         "<redacted>",
	}
	return jsonMarshalIndent(bridgeDTOContract{
		SessionConfig: bridgeSessionConfig{
			ServerAddr:        "bridge-frps.invalid",
			ServerPort:        7000,
			AuthToken:         "<redacted>",
			User:              "bridge-user",
			TransportProtocol: "tcp",
			WireProtocol:      "v2",
		},
		SessionConfigDefaults: bridgeSessionConfigDefaults{
			ServerAddr: "bridge-defaults-frps.invalid",
			ServerPort: 7000,
			AuthToken:  "<redacted>",
		},
		VisitorConfig: bridgeVisitorConfig{
			Name:           "bridge-visitor",
			ServerName:     "bridge-server",
			ServerUser:     "bridge-server-user",
			SecretKey:      "<redacted>",
			BindAddr:       "127.0.0.1",
			BindPort:       4096,
			UseEncryption:  false,
			UseCompression: false,
		},
		VisitorConfigDefaults: bridgeVisitorConfigDefaults{
			Name:       "bridge-defaults-visitor",
			ServerName: "bridge-defaults-server",
			SecretKey:  "<redacted>",
			BindPort:   4096,
		},
		EnsureVisitorResult: bridgeEnsureVisitorResult{
			BindPort:        4096,
			DesiredRevision: desiredRevision,
		},
		VisitorReadyResult: bridgeVisitorReadyResult{
			Name:              "bridge-visitor",
			DesiredRevision:   desiredRevision,
			Phase:             "ready",
			ListenerBound:     true,
			BoundControlEpoch: controlEpoch,
			LastError:         "<redacted>",
		},
		VisitorReadyResultWithoutError: bridgeVisitorReadyResultWithoutError{
			Name:              "bridge-defaults-visitor",
			DesiredRevision:   desiredRevision,
			Phase:             "ready",
			ListenerBound:     true,
			BoundControlEpoch: controlEpoch,
		},
		VisitorRuntimeState: runtimeState,
		VisitorRuntimeStateWithoutError: bridgeVisitorRuntimeStateWithoutError{
			DesiredRevision:   desiredRevision,
			Phase:             "pending",
			ListenerBound:     false,
			BoundControlEpoch: 0,
		},
		State: bridgeState{
			SessionID:      "bridge-session",
			Phase:          "open",
			ConfigRevision: configRevision,
			ControlEpoch:   controlEpoch,
			LastError:      "<redacted>",
			Visitors: map[string]bridgeVisitorRuntimeState{
				"bridge-visitor": runtimeState,
			},
		},
		StateWithoutOptionalFields: bridgeStateWithoutOptionalFields{
			SessionID:      "bridge-defaults-session",
			Phase:          "closed",
			ConfigRevision: 0,
			ControlEpoch:   0,
		},
		StopSessionResult: bridgeStopSessionResult{
			SessionID: "bridge-session",
			Phase:     "closed",
		},
	})
}
