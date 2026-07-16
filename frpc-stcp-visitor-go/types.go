package frpcstcpvisitor

type sessionConfig struct {
	ServerAddr        string `json:"serverAddr"`
	ServerPort        int    `json:"serverPort"`
	AuthToken         string `json:"authToken"`
	User              string `json:"user,omitempty"`
	TransportProtocol string `json:"transportProtocol"`
	WireProtocol      string `json:"wireProtocol"`
}

type visitorConfig struct {
	Name           string `json:"name"`
	ServerName     string `json:"serverName"`
	ServerUser     string `json:"serverUser,omitempty"`
	SecretKey      string `json:"secretKey"`
	BindAddr       string `json:"bindAddr"`
	BindPort       int    `json:"bindPort"`
	UseEncryption  bool   `json:"useEncryption"`
	UseCompression bool   `json:"useCompression"`
}

type ensureVisitorResult struct {
	BindPort        int    `json:"bindPort"`
	DesiredRevision uint64 `json:"desiredRevision"`
}

type visitorReadyResult struct {
	Name              string `json:"name"`
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
	LastError         string `json:"lastError,omitempty"`
}

type visitorRuntimeState struct {
	DesiredRevision   uint64 `json:"desiredRevision"`
	Phase             string `json:"phase"`
	ListenerBound     bool   `json:"listenerBound"`
	BoundControlEpoch uint64 `json:"boundControlEpoch"`
	LastError         string `json:"lastError,omitempty"`
}

type state struct {
	SessionID      string                         `json:"sessionId"`
	Phase          string                         `json:"phase"`
	ConfigRevision uint64                         `json:"configRevision"`
	ControlEpoch   uint64                         `json:"controlEpoch"`
	LastError      string                         `json:"lastError,omitempty"`
	Visitors       map[string]visitorRuntimeState `json:"visitors,omitempty"`
}

type stopSessionResult struct {
	SessionID string `json:"sessionId"`
	Phase     string `json:"phase"`
}
