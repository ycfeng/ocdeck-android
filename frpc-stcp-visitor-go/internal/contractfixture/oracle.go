package contractfixture

import (
	"bytes"
	cryptorand "crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"reflect"
	"sort"
	"strings"
	"sync"

	libcrypto "github.com/fatedier/golib/crypto"
	jsonmsg "github.com/fatedier/golib/msg/json"

	"github.com/fatedier/frp/pkg/auth"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/msg"
	"github.com/fatedier/frp/pkg/proto/wire"
	netpkg "github.com/fatedier/frp/pkg/util/net"
	"github.com/fatedier/frp/pkg/util/util"
)

const (
	syntheticToken      = "ocdeck-k0-obviously-synthetic-token"
	syntheticSTCPSecret = "ocdeck-k0-obviously-synthetic-stcp-secret"

	wireV1MaxJSONLength   = int64(10240)
	yamuxHeaderSize       = 12
	yamuxInitialWindow    = uint32(256 * 1024)
	yamuxFRPStreamWindow  = uint32(6 * 1024 * 1024)
	yamuxVersion          = uint8(0)
	yamuxTypeData         = uint8(0)
	yamuxTypeWindowUpdate = uint8(1)
	yamuxTypePing         = uint8(2)
	yamuxTypeGoAway       = uint8(3)
	yamuxFlagSYN          = uint16(1)
	yamuxFlagACK          = uint16(2)
	yamuxFlagFIN          = uint16(4)
	yamuxFlagRST          = uint16(8)
)

const (
	loginTimestamp   = int64(1_700_000_000)
	pingTimestamp    = int64(1_700_000_060)
	workTimestamp    = int64(1_700_000_120)
	visitorTimestamp = int64(1_700_000_180)
)

var cryptoRandMutex sync.Mutex

type oracleSpec struct {
	login           *msg.Login
	loginResponse   *msg.LoginResp
	visitor         *msg.NewVisitorConn
	visitorResponse *msg.NewVisitorConnResp
	ping            *msg.Ping
	newWork         *msg.NewWorkConn
	startWork       *msg.StartWorkConn
	pong            *msg.Pong
	reqWork         *msg.ReqWorkConn
	profiles        map[string]*cryptoProfile
	controls        map[string]*controlVector
	yamuxTraces     map[string][]yamuxFrame
	clientMessages  []string
	serverMessages  []string
}

type cryptoProfile struct {
	name              string
	algorithm         string
	clientHello       wire.ClientHello
	serverHello       wire.ServerHello
	clientHelloFrame  *wire.Frame
	serverHelloFrame  *wire.Frame
	transcriptHash    []byte
	clientStreamNonce []byte
	serverStreamNonce []byte
}

type controlVector struct {
	plainPath  string
	cipherPath string
	protocol   string
	algorithm  string
	writeRole  netpkg.AEADCryptoRole
	readRole   netpkg.AEADCryptoRole
	profile    *cryptoProfile
	plain      []byte
	parts      [][]byte
	messages   []string
}

type yamuxFrame struct {
	messageType uint8
	flags       uint16
	streamID    uint32
	length      uint32
	payload     []byte
}

type readWriter struct {
	io.Reader
	io.Writer
}

type chunkReader struct {
	reader io.Reader
	sizes  []int
	index  int
}

func generateFixtureSet(root string) (*generatedSet, error) {
	pinSet, err := readPins(root)
	if err != nil {
		return nil, err
	}
	spec, files, entries, err := buildOracleArtifacts()
	if err != nil {
		return nil, err
	}

	manifestValue := manifest{
		SchemaVersion:    schemaVersion,
		GeneratorVersion: generatorVersion,
		Pins:             pinSet,
		Provenance: provenance{
			Synthetic:               true,
			Generator:               "frpc-stcp-visitor-go/cmd/contractfixture",
			Source:                  "Deterministic Go oracle over the bridge DTO contract, pinned frp/golib/snappy APIs, and traffic recorded from paired pinned yamux Client/Server sessions over in-memory connections; no captured external traffic.",
			License:                 "MIT",
			ContainsCapturedTraffic: false,
			ContainsRawSecrets:      false,
		},
		Limits: limits{
			WireV1JSONLength:     wireV1MaxJSONLength,
			WireV2FramePayload:   wire.DefaultMaxFramePayloadSize,
			YamuxHeader:          yamuxHeaderSize,
			YamuxInitialWindow:   yamuxInitialWindow,
			YamuxFRPStreamWindow: yamuxFRPStreamWindow,
		},
		Entries: entries,
		ChunkPlans: []chunkPlan{
			{ID: "control-v2-aes-record-splits", Source: "control/v2/aes-256-gcm/client-to-server.bin", Sizes: []int{1, 3, 2, 7}, Expected: "decrypt-success"},
			{ID: "snappy-cfb-framed-splits", Source: snappyCFBFramedCompressedPath, Sizes: []int{1, 2, 3, 5, 8}, Expected: "decrypt-then-decompress-success"},
			{ID: "wire-v1-bytewise", Source: "wire/v1/login-token.bin", Sizes: []int{1}, Expected: "decode-success"},
			{ID: "wire-v2-frame-splits", Source: "wire/v2/aes-256-gcm/client-bootstrap.bin", Sizes: []int{1, 2, 3, 5, 8}, Expected: "decode-success"},
			{ID: "yamux-flow-control-splits", Source: yamuxFlowControlClientTracePath, Sizes: []int{1, 11, 4096, 3}, Expected: "parse-success"},
			{ID: "yamux-lifecycle-splits", Source: yamuxLifecycleClientTracePath, Sizes: []int{1, 11, 2, 5}, Expected: "parse-success"},
			{ID: "yamux-reset-splits", Source: yamuxResetServerTracePath, Sizes: []int{2, 1, 9, 4}, Expected: "parse-success"},
		},
	}
	manifestValue.Mutations, err = buildMutationRecipes(files, spec)
	if err != nil {
		return nil, err
	}
	sort.Slice(manifestValue.ChunkPlans, func(i, j int) bool { return manifestValue.ChunkPlans[i].ID < manifestValue.ChunkPlans[j].ID })
	sort.Slice(manifestValue.Mutations, func(i, j int) bool { return manifestValue.Mutations[i].ID < manifestValue.Mutations[j].ID })

	manifestData, err := jsonMarshalIndent(manifestValue)
	if err != nil {
		return nil, fmt.Errorf("encode fixture manifest: %w", err)
	}
	files[manifestFileName] = manifestData
	return &generatedSet{manifest: manifestValue, files: files, spec: spec}, nil
}

func buildOracleArtifacts() (*oracleSpec, map[string][]byte, []entry, error) {
	scopes := []v1.AuthScope{v1.AuthScopeHeartBeats, v1.AuthScopeNewWorkConns}
	tokenAuth := auth.NewTokenAuth(scopes, syntheticToken)
	login := &msg.Login{
		Version:   "0.69.1-fixture",
		Hostname:  "fixture-client",
		Os:        "android",
		Arch:      "arm64",
		User:      "fixture-user",
		Timestamp: loginTimestamp,
		PoolCount: 1,
	}
	if err := tokenAuth.SetLogin(login); err != nil {
		return nil, nil, nil, fmt.Errorf("construct token login fixture")
	}
	ping := &msg.Ping{
		PrivilegeKey: util.GetAuthKey(syntheticToken, pingTimestamp),
		Timestamp:    pingTimestamp,
	}
	newWork := &msg.NewWorkConn{
		RunID:        "run_fixture_001",
		PrivilegeKey: util.GetAuthKey(syntheticToken, workTimestamp),
		Timestamp:    workTimestamp,
	}
	visitor := &msg.NewVisitorConn{
		RunID:          "run_fixture_001",
		ProxyName:      "fixture-user.fixture-service",
		SignKey:        util.GetAuthKey(syntheticSTCPSecret, visitorTimestamp),
		Timestamp:      visitorTimestamp,
		UseEncryption:  false,
		UseCompression: false,
	}
	loginResponse := &msg.LoginResp{
		Version: "0.69.1-fixture",
		RunID:   "run_fixture_001",
	}
	startWork := &msg.StartWorkConn{
		ProxyName: visitor.ProxyName,
		SrcAddr:   "127.0.0.1",
		DstAddr:   "127.0.0.1",
		SrcPort:   7000,
		DstPort:   4096,
	}
	visitorResponse := &msg.NewVisitorConnResp{ProxyName: visitor.ProxyName}
	spec := &oracleSpec{
		login:           login,
		loginResponse:   loginResponse,
		visitor:         visitor,
		visitorResponse: visitorResponse,
		ping:            ping,
		newWork:         newWork,
		startWork:       startWork,
		pong:            &msg.Pong{},
		reqWork:         &msg.ReqWorkConn{},
		profiles:        make(map[string]*cryptoProfile),
		controls:        make(map[string]*controlVector),
		yamuxTraces:     make(map[string][]yamuxFrame),
		clientMessages:  []string{"ping", "new-work"},
		serverMessages:  []string{"pong", "req-work"},
	}
	if err := validateAuthInputs(spec); err != nil {
		return nil, nil, nil, err
	}

	aesProfile, err := newCryptoProfile(
		"aes-256-gcm",
		wire.AEADAlgorithmAES256GCM,
		[]string{wire.AEADAlgorithmAES256GCM, wire.AEADAlgorithmXChaCha20Poly1305},
		0x00,
		0x20,
		0xa0,
		0xb0,
	)
	if err != nil {
		return nil, nil, nil, err
	}
	xChaChaProfile, err := newCryptoProfile(
		"xchacha20-poly1305",
		wire.AEADAlgorithmXChaCha20Poly1305,
		[]string{wire.AEADAlgorithmXChaCha20Poly1305, wire.AEADAlgorithmAES256GCM},
		0x40,
		0x60,
		0xc0,
		0xe0,
	)
	if err != nil {
		return nil, nil, nil, err
	}
	spec.profiles[aesProfile.name] = aesProfile
	spec.profiles[xChaChaProfile.name] = xChaChaProfile

	files := make(map[string][]byte)
	entries := make([]entry, 0, 34)
	add := func(id, path, layer, expected string, data []byte) error {
		if _, exists := files[path]; exists {
			return fmt.Errorf("duplicate generated fixture path %s", path)
		}
		copyOfData := append([]byte(nil), data...)
		files[path] = copyOfData
		entries = append(entries, entry{ID: id, Path: path, Layer: layer, Length: len(copyOfData), SHA256: sha256Hex(copyOfData), Expected: expected})
		return nil
	}
	bridgeDTOData, err := buildBridgeDTOContract()
	if err != nil {
		return nil, nil, nil, fmt.Errorf("construct bridge DTO contract: %w", err)
	}
	if err := add(
		"bridge-dto-contract",
		"bridge/dto-contract.json",
		"bridge-json",
		"Go/Kotlin DTO decode and encode semantics match",
		bridgeDTOData,
	); err != nil {
		return nil, nil, nil, err
	}

	v1LoginParts, err := encodeMessageParts(wire.ProtocolV1, []msg.Message{login})
	if err != nil {
		return nil, nil, nil, err
	}
	v1ServerStandaloneParts, err := encodeMessageParts(
		wire.ProtocolV1,
		[]msg.Message{spec.loginResponse, spec.startWork, spec.visitorResponse},
	)
	if err != nil {
		return nil, nil, nil, err
	}
	v1VisitorParts, err := encodeMessageParts(wire.ProtocolV1, []msg.Message{visitor})
	if err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v1-login-response", "wire/v1/login-response.bin", "wire-v1", "decode-login-response; run-id=present; error=empty", v1ServerStandaloneParts[0]); err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v1-login-token", "wire/v1/login-token.bin", "wire-v1", "decode-login; token-auth=valid", v1LoginParts[0]); err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v1-new-visitor-conn-response", "wire/v1/new-visitor-conn-response.bin", "wire-v1", "decode-new-visitor-conn-response; proxy=present; error=empty", v1ServerStandaloneParts[2]); err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v1-new-visitor-stcp", "wire/v1/new-visitor-stcp.bin", "wire-v1", "decode-new-visitor; stcp-auth=valid", v1VisitorParts[0]); err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v1-start-work-conn", "wire/v1/start-work-conn.bin", "wire-v1", "decode-start-work-conn; proxy-addresses-ports=present; error=empty", v1ServerStandaloneParts[1]); err != nil {
		return nil, nil, nil, err
	}

	for _, profileName := range []string{"aes-256-gcm", "xchacha20-poly1305"} {
		profile := spec.profiles[profileName]
		clientBootstrap, err := encodeClientBootstrap(profile, login)
		if err != nil {
			return nil, nil, nil, err
		}
		serverBootstrap, err := encodeServerBootstrap(profile, spec.loginResponse)
		if err != nil {
			return nil, nil, nil, err
		}
		if err := add("wire-v2-"+profileName+"-client-bootstrap", "wire/v2/"+profileName+"/client-bootstrap.bin", "wire-v2-bootstrap", "magic; client-hello; login; token-auth=valid", clientBootstrap); err != nil {
			return nil, nil, nil, err
		}
		if err := add("wire-v2-"+profileName+"-server-bootstrap", "wire/v2/"+profileName+"/server-bootstrap.bin", "wire-v2-bootstrap", "server-hello; login-response", serverBootstrap); err != nil {
			return nil, nil, nil, err
		}
	}
	v2VisitorParts, err := encodeMessageParts(wire.ProtocolV2, []msg.Message{visitor})
	if err != nil {
		return nil, nil, nil, err
	}
	v2ServerStandaloneParts, err := encodeMessageParts(
		wire.ProtocolV2,
		[]msg.Message{spec.startWork, spec.visitorResponse},
	)
	if err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v2-new-visitor-conn-response", "wire/v2/new-visitor-conn-response.bin", "wire-v2", "decode-new-visitor-conn-response; proxy=present; error=empty", v2ServerStandaloneParts[1]); err != nil {
		return nil, nil, nil, err
	}
	v2Visitor := append([]byte(wire.MagicV2), v2VisitorParts[0]...)
	if err := add("wire-v2-new-visitor-stcp", "wire/v2/new-visitor-stcp.bin", "wire-v2", "magic; decode-new-visitor; stcp-auth=valid", v2Visitor); err != nil {
		return nil, nil, nil, err
	}
	if err := add("wire-v2-start-work-conn", "wire/v2/start-work-conn.bin", "wire-v2", "decode-start-work-conn; proxy-addresses-ports=present; error=empty", v2ServerStandaloneParts[0]); err != nil {
		return nil, nil, nil, err
	}

	v1ClientParts, err := encodeMessageParts(wire.ProtocolV1, []msg.Message{ping, newWork})
	if err != nil {
		return nil, nil, nil, err
	}
	v1ServerParts, err := encodeMessageParts(wire.ProtocolV1, []msg.Message{spec.pong, spec.reqWork})
	if err != nil {
		return nil, nil, nil, err
	}
	v1ClientPlain := concatParts(v1ClientParts)
	v1ServerPlain := concatParts(v1ServerParts)
	v1ClientCipher, err := encryptCFB(v1ClientParts, fixedBytes(0x80, 16))
	if err != nil {
		return nil, nil, nil, err
	}
	v1ServerCipher, err := encryptCFB(v1ServerParts, fixedBytes(0x90, 16))
	if err != nil {
		return nil, nil, nil, err
	}
	if err := add("control-v1-client-plain", "control/v1/plain/client-to-server.bin", "control-v1-plaintext", "messages=Ping,NewWorkConn", v1ClientPlain); err != nil {
		return nil, nil, nil, err
	}
	if err := add("control-v1-server-plain", "control/v1/plain/server-to-client.bin", "control-v1-plaintext", "messages=Pong,ReqWorkConn", v1ServerPlain); err != nil {
		return nil, nil, nil, err
	}
	if err := add("control-v1-client-cfb", "control/v1/cfb/client-to-server.bin", "control-v1-cfb", "decrypt=client-to-server plaintext", v1ClientCipher); err != nil {
		return nil, nil, nil, err
	}
	if err := add("control-v1-server-cfb", "control/v1/cfb/server-to-client.bin", "control-v1-cfb", "decrypt=server-to-client plaintext", v1ServerCipher); err != nil {
		return nil, nil, nil, err
	}
	spec.controls["control/v1/cfb/client-to-server.bin"] = &controlVector{
		plainPath: "control/v1/plain/client-to-server.bin", cipherPath: "control/v1/cfb/client-to-server.bin", protocol: wire.ProtocolV1,
		plain: v1ClientPlain, parts: v1ClientParts, messages: spec.clientMessages,
	}
	spec.controls["control/v1/cfb/server-to-client.bin"] = &controlVector{
		plainPath: "control/v1/plain/server-to-client.bin", cipherPath: "control/v1/cfb/server-to-client.bin", protocol: wire.ProtocolV1,
		plain: v1ServerPlain, parts: v1ServerParts, messages: spec.serverMessages,
	}

	v2ClientParts, err := encodeMessageParts(wire.ProtocolV2, []msg.Message{ping, newWork})
	if err != nil {
		return nil, nil, nil, err
	}
	v2ServerParts, err := encodeMessageParts(wire.ProtocolV2, []msg.Message{spec.pong, spec.reqWork})
	if err != nil {
		return nil, nil, nil, err
	}
	v2ClientPlain := concatParts(v2ClientParts)
	v2ServerPlain := concatParts(v2ServerParts)
	if err := add("control-v2-client-plain", "control/v2/plain/client-to-server.bin", "control-v2-plaintext", "messages=Ping,NewWorkConn", v2ClientPlain); err != nil {
		return nil, nil, nil, err
	}
	if err := add("control-v2-server-plain", "control/v2/plain/server-to-client.bin", "control-v2-plaintext", "messages=Pong,ReqWorkConn", v2ServerPlain); err != nil {
		return nil, nil, nil, err
	}
	for _, profileName := range []string{"aes-256-gcm", "xchacha20-poly1305"} {
		profile := spec.profiles[profileName]
		clientCipher, err := encryptAEAD(v2ClientParts, netpkg.AEADCryptoRoleClient, profile, profile.clientStreamNonce)
		if err != nil {
			return nil, nil, nil, err
		}
		serverCipher, err := encryptAEAD(v2ServerParts, netpkg.AEADCryptoRoleServer, profile, profile.serverStreamNonce)
		if err != nil {
			return nil, nil, nil, err
		}
		clientPath := "control/v2/" + profileName + "/client-to-server.bin"
		serverPath := "control/v2/" + profileName + "/server-to-client.bin"
		if err := add("control-v2-"+profileName+"-client", clientPath, "control-v2-aead", "decrypt=server-role; integrity=authenticated", clientCipher); err != nil {
			return nil, nil, nil, err
		}
		if err := add("control-v2-"+profileName+"-server", serverPath, "control-v2-aead", "decrypt=client-role; integrity=authenticated", serverCipher); err != nil {
			return nil, nil, nil, err
		}
		spec.controls[clientPath] = &controlVector{
			plainPath: "control/v2/plain/client-to-server.bin", cipherPath: clientPath, protocol: wire.ProtocolV2,
			algorithm: profile.algorithm, writeRole: netpkg.AEADCryptoRoleClient, readRole: netpkg.AEADCryptoRoleServer,
			profile: profile, plain: v2ClientPlain, parts: v2ClientParts, messages: spec.clientMessages,
		}
		spec.controls[serverPath] = &controlVector{
			plainPath: "control/v2/plain/server-to-client.bin", cipherPath: serverPath, protocol: wire.ProtocolV2,
			algorithm: profile.algorithm, writeRole: netpkg.AEADCryptoRoleServer, readRole: netpkg.AEADCryptoRoleClient,
			profile: profile, plain: v2ServerPlain, parts: v2ServerParts, messages: spec.serverMessages,
		}
	}

	snappyArtifacts, err := buildSnappyArtifacts()
	if err != nil {
		return nil, nil, nil, err
	}
	for _, artifact := range snappyArtifacts {
		if err := add(artifact.id, artifact.path, artifact.layer, artifact.expected, artifact.data); err != nil {
			return nil, nil, nil, err
		}
	}

	yamuxArtifacts, err := buildYamuxArtifacts()
	if err != nil {
		return nil, nil, nil, err
	}
	for _, artifact := range yamuxArtifacts {
		if err := add(artifact.id, artifact.path, "yamux", artifact.expected, artifact.data); err != nil {
			return nil, nil, nil, err
		}
		spec.yamuxTraces[artifact.path] = artifact.frames
	}

	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	return spec, files, entries, nil
}

func validateAuthInputs(spec *oracleSpec) error {
	tokenAuth := auth.NewTokenAuth([]v1.AuthScope{v1.AuthScopeHeartBeats, v1.AuthScopeNewWorkConns}, syntheticToken)
	if tokenAuth.VerifyLogin(spec.login) != nil || tokenAuth.VerifyPing(spec.ping) != nil || tokenAuth.VerifyNewWorkConn(spec.newWork) != nil {
		return fmt.Errorf("synthetic token authentication inputs are invalid")
	}
	if util.GetAuthKey(syntheticSTCPSecret, spec.visitor.Timestamp) != spec.visitor.SignKey {
		return fmt.Errorf("synthetic STCP authentication input is invalid")
	}
	if spec.visitor.UseEncryption || spec.visitor.UseCompression {
		return fmt.Errorf("STCP visitor fixture must disable encryption and compression")
	}
	return nil
}

func newCryptoProfile(name, algorithm string, algorithms []string, clientRandomStart, serverRandomStart, clientNonceStart, serverNonceStart byte) (*cryptoProfile, error) {
	clientHello := wire.ClientHello{
		Bootstrap: wire.BootstrapInfo{Transport: "tcp", TLS: true, TCPMux: true},
		Capabilities: wire.ClientCapabilities{
			Message: wire.MessageCapabilities{Codecs: []string{wire.MessageCodecJSON}},
			Crypto:  wire.CryptoCapabilities{Algorithms: append([]string(nil), algorithms...), ClientRandom: fixedBytes(clientRandomStart, wire.CryptoRandomSize)},
		},
	}
	if err := wire.ValidateClientHello(clientHello); err != nil {
		return nil, fmt.Errorf("validate fixed client hello for %s", name)
	}
	selected, ok := wire.SelectAEADAlgorithm(clientHello.Capabilities.Crypto.Algorithms)
	if !ok || selected != algorithm {
		return nil, fmt.Errorf("fixed client hello selection changed for %s", name)
	}
	serverHello := wire.ServerHello{
		Selected: wire.ServerSelection{
			Message: wire.MessageSelection{Codec: wire.MessageCodecJSON},
			Crypto:  wire.CryptoSelection{Algorithm: algorithm, ServerRandom: fixedBytes(serverRandomStart, wire.CryptoRandomSize)},
		},
	}
	if err := wire.ValidateServerHelloForClient(clientHello, serverHello); err != nil {
		return nil, fmt.Errorf("validate fixed server hello for %s", name)
	}
	clientFrame, err := wire.NewJSONFrame(wire.FrameTypeClientHello, clientHello)
	if err != nil {
		return nil, fmt.Errorf("encode fixed client hello for %s", name)
	}
	serverFrame, err := wire.NewJSONFrame(wire.FrameTypeServerHello, serverHello)
	if err != nil {
		return nil, fmt.Errorf("encode fixed server hello for %s", name)
	}
	contextFromServer := wire.NewCryptoContext(algorithm, clientFrame.Payload, serverFrame.Payload)
	contextFromClient, err := wire.NewClientCryptoContext(clientFrame.Payload, serverFrame.Payload)
	if err != nil || contextFromClient.Algorithm != contextFromServer.Algorithm || !bytes.Equal(contextFromClient.TranscriptHash, contextFromServer.TranscriptHash) {
		return nil, fmt.Errorf("fixed crypto transcript differs by role for %s", name)
	}
	nonceSize := 12
	if algorithm == wire.AEADAlgorithmXChaCha20Poly1305 {
		nonceSize = 24
	}
	return &cryptoProfile{
		name: name, algorithm: algorithm, clientHello: clientHello, serverHello: serverHello,
		clientHelloFrame: clientFrame, serverHelloFrame: serverFrame, transcriptHash: contextFromServer.TranscriptHash,
		clientStreamNonce: fixedBytes(clientNonceStart, nonceSize), serverStreamNonce: fixedBytes(serverNonceStart, nonceSize),
	}, nil
}

func encodeClientBootstrap(profile *cryptoProfile, login *msg.Login) ([]byte, error) {
	var buffer bytes.Buffer
	if err := wire.WriteMagic(&buffer); err != nil {
		return nil, err
	}
	wireConn := wire.NewConn(&buffer)
	if err := wireConn.WriteFrame(profile.clientHelloFrame); err != nil {
		return nil, err
	}
	if err := msg.NewV2ReadWriterWithConn(wireConn).WriteMsg(login); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func encodeServerBootstrap(profile *cryptoProfile, response *msg.LoginResp) ([]byte, error) {
	var buffer bytes.Buffer
	wireConn := wire.NewConn(&buffer)
	if err := wireConn.WriteFrame(profile.serverHelloFrame); err != nil {
		return nil, err
	}
	if err := msg.NewV2ReadWriterWithConn(wireConn).WriteMsg(response); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func encodeMessageParts(protocol string, messages []msg.Message) ([][]byte, error) {
	parts := make([][]byte, 0, len(messages))
	for range messages {
		parts = append(parts, nil)
	}
	for index, message := range messages {
		var buffer bytes.Buffer
		if err := msg.NewReadWriter(&buffer, protocol).WriteMsg(message); err != nil {
			return nil, fmt.Errorf("encode %s message %d", protocol, index)
		}
		parts[index] = append([]byte(nil), buffer.Bytes()...)
	}
	return parts, nil
}

func concatParts(parts [][]byte) []byte {
	var length int
	for _, part := range parts {
		length += len(part)
	}
	result := make([]byte, 0, length)
	for _, part := range parts {
		result = append(result, part...)
	}
	return result
}

func encryptCFB(parts [][]byte, iv []byte) ([]byte, error) {
	var output bytes.Buffer
	err := withFixedCryptoRand(iv, func() error {
		cryptoRW, err := netpkg.NewCryptoReadWriter(&output, []byte(syntheticToken))
		if err != nil {
			return err
		}
		for _, part := range parts {
			if err := writeAll(cryptoRW, part); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("encrypt deterministic CFB control fixture")
	}
	return output.Bytes(), nil
}

func encryptAEAD(parts [][]byte, role netpkg.AEADCryptoRole, profile *cryptoProfile, nonce []byte) ([]byte, error) {
	var output bytes.Buffer
	err := withFixedCryptoRand(nonce, func() error {
		cryptoRW, err := netpkg.NewAEADCryptoReadWriter(&output, []byte(syntheticToken), role, profile.algorithm, profile.transcriptHash)
		if err != nil {
			return err
		}
		for _, part := range parts {
			if err := writeAll(cryptoRW, part); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("encrypt deterministic AEAD control fixture for %s", profile.name)
	}
	return output.Bytes(), nil
}

func withFixedCryptoRand(random []byte, operation func() error) error {
	cryptoRandMutex.Lock()
	defer cryptoRandMutex.Unlock()
	reader := bytes.NewReader(random)
	previousReader := cryptorand.Reader
	previousSalt := libcrypto.DefaultSalt
	cryptorand.Reader = reader
	libcrypto.DefaultSalt = "frp"
	defer func() {
		cryptorand.Reader = previousReader
		libcrypto.DefaultSalt = previousSalt
	}()
	if err := operation(); err != nil {
		return err
	}
	if reader.Len() != 0 {
		return fmt.Errorf("deterministic crypto random was not fully consumed")
	}
	return nil
}

func writeAll(writer io.Writer, data []byte) error {
	for len(data) > 0 {
		written, err := writer.Write(data)
		if written < 0 || written > len(data) {
			return io.ErrShortWrite
		}
		data = data[written:]
		if err != nil {
			return err
		}
		if written == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}

func fixedBytes(start byte, count int) []byte {
	result := make([]byte, count)
	for index := range result {
		result[index] = start + byte(index)
	}
	return result
}

func buildMutationRecipes(files map[string][]byte, spec *oracleSpec) ([]mutation, error) {
	v1Control := spec.controls["control/v1/cfb/client-to-server.bin"]
	if len(v1Control.plain) < 2 || v1Control.plain[len(v1Control.plain)-1] != '}' || v1Control.plain[len(v1Control.plain)-2] < '0' || v1Control.plain[len(v1Control.plain)-2] > '9' {
		return nil, fmt.Errorf("v1 control plaintext no longer ends with a mutable timestamp digit")
	}
	v1CipherOffset := 16 + len(v1Control.plain) - 2
	if v1CipherOffset >= len(files[v1Control.cipherPath]) {
		return nil, fmt.Errorf("v1 control mutation offset is invalid")
	}
	yamuxPayloadOffset, err := firstYamuxDataPayloadOffset(files[yamuxLifecycleClientTracePath])
	if err != nil {
		return nil, err
	}

	return []mutation{
		{ID: "control-v1-cfb-bit-flip", Source: v1Control.cipherPath, Operation: "xor-byte", Offset: intPtr(v1CipherOffset), Mask: bytePtr(0x01), Expected: "accept-altered"},
		{ID: "control-v2-aes-bit-flip", Source: "control/v2/aes-256-gcm/client-to-server.bin", Operation: "xor-byte", Offset: intPtr(len(files["control/v2/aes-256-gcm/client-to-server.bin"]) - 1), Mask: bytePtr(0x01), Expected: "integrity-failure"},
		{ID: "control-v2-aes-record-reorder", Source: "control/v2/aes-256-gcm/client-to-server.bin", Operation: "swap-aead-records", Expected: "integrity-failure"},
		{ID: "control-v2-aes-truncated", Source: "control/v2/aes-256-gcm/client-to-server.bin", Operation: "truncate-tail", Count: intPtr(1), Expected: "reject-truncated"},
		{ID: "control-v2-aes-wrong-role", Source: "control/v2/aes-256-gcm/server-to-client.bin", Operation: "wrong-aead-role", Expected: "integrity-failure"},
		{ID: "control-v2-xchacha-bit-flip", Source: "control/v2/xchacha20-poly1305/client-to-server.bin", Operation: "xor-byte", Offset: intPtr(len(files["control/v2/xchacha20-poly1305/client-to-server.bin"]) - 1), Mask: bytePtr(0x01), Expected: "integrity-failure"},
		{ID: "control-v2-xchacha-record-reorder", Source: "control/v2/xchacha20-poly1305/client-to-server.bin", Operation: "swap-aead-records", Expected: "integrity-failure"},
		{ID: "control-v2-xchacha-wrong-role", Source: "control/v2/xchacha20-poly1305/server-to-client.bin", Operation: "wrong-aead-role", Expected: "integrity-failure"},
		{ID: "wire-v1-length-max-plus-one", Source: "wire/v1/login-token.bin", Operation: "set-int64-be", Offset: intPtr(1), IntValue: int64Ptr(wireV1MaxJSONLength + 1), Expected: "reject-over-limit"},
		{ID: "wire-v1-negative-length", Source: "wire/v1/login-token.bin", Operation: "set-int64-be", Offset: intPtr(1), IntValue: int64Ptr(-1), Expected: "reject-negative-length"},
		{ID: "wire-v1-truncated", Source: "wire/v1/login-token.bin", Operation: "truncate-tail", Count: intPtr(1), Expected: "reject-truncated"},
		{ID: "wire-v1-unknown-type", Source: "wire/v1/login-token.bin", Operation: "set-byte", Offset: intPtr(0), ByteValue: bytePtr(0xff), Expected: "reject-unknown-type"},
		{ID: "wire-v2-error-flags", Source: "wire/v2/aes-256-gcm/client-bootstrap.bin", Operation: "set-uint16-be", Offset: intPtr(len(wire.MagicV2) + 2), IntValue: int64Ptr(1), Expected: "reject-flags"},
		{ID: "wire-v2-payload-max-plus-one", Source: "wire/v2/aes-256-gcm/client-bootstrap.bin", Operation: "set-uint32-be", Offset: intPtr(len(wire.MagicV2) + 4), IntValue: int64Ptr(int64(wire.DefaultMaxFramePayloadSize) + 1), Expected: "reject-over-limit"},
		{ID: "wire-v2-truncated", Source: "wire/v2/aes-256-gcm/client-bootstrap.bin", Operation: "truncate-tail", Count: intPtr(1), Expected: "reject-truncated"},
		{ID: "wire-v2-unknown-message-type", Source: "wire/v2/new-visitor-stcp.bin", Operation: "set-uint16-be", Offset: intPtr(len(wire.MagicV2) + 8), IntValue: int64Ptr(0xffff), Expected: "reject-unknown-type"},
		{ID: "yamux-data-bit-flip", Source: yamuxLifecycleClientTracePath, Operation: "xor-byte", Offset: intPtr(yamuxPayloadOffset), Mask: bytePtr(0x01), Expected: "accept-altered"},
		{ID: "yamux-unknown-type", Source: yamuxLifecycleClientTracePath, Operation: "set-byte", Offset: intPtr(1), ByteValue: bytePtr(0xff), Expected: "reject-unknown-type"},
	}, nil
}

func intPtr(value int) *int       { return &value }
func int64Ptr(value int64) *int64 { return &value }
func bytePtr(value uint8) *uint8  { return &value }

func jsonMarshalIndent(value any) ([]byte, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(value); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func validateOracleSemantics(files map[string][]byte, spec *oracleSpec, manifestValue manifest) error {
	if err := validateMessageSequence(bytes.NewReader(files["wire/v1/login-response.bin"]), wire.ProtocolV1, []string{"login-response"}, spec); err != nil {
		return fmt.Errorf("validate wire v1 login response")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v1/login-token.bin"]), wire.ProtocolV1, []string{"login"}, spec); err != nil {
		return fmt.Errorf("validate wire v1 login")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v1/new-visitor-conn-response.bin"]), wire.ProtocolV1, []string{"visitor-response"}, spec); err != nil {
		return fmt.Errorf("validate wire v1 visitor response")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v1/new-visitor-stcp.bin"]), wire.ProtocolV1, []string{"visitor"}, spec); err != nil {
		return fmt.Errorf("validate wire v1 visitor")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v1/start-work-conn.bin"]), wire.ProtocolV1, []string{"start-work"}, spec); err != nil {
		return fmt.Errorf("validate wire v1 start work connection")
	}
	for _, profileName := range []string{"aes-256-gcm", "xchacha20-poly1305"} {
		profile := spec.profiles[profileName]
		if err := validateClientBootstrap(bytes.NewReader(files["wire/v2/"+profileName+"/client-bootstrap.bin"]), profile, spec); err != nil {
			return fmt.Errorf("validate wire v2 %s client bootstrap", profileName)
		}
		if err := validateServerBootstrap(bytes.NewReader(files["wire/v2/"+profileName+"/server-bootstrap.bin"]), profile, spec); err != nil {
			return fmt.Errorf("validate wire v2 %s server bootstrap", profileName)
		}
	}
	if err := validateV2Visitor(bytes.NewReader(files["wire/v2/new-visitor-stcp.bin"]), spec); err != nil {
		return fmt.Errorf("validate wire v2 visitor")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v2/new-visitor-conn-response.bin"]), wire.ProtocolV2, []string{"visitor-response"}, spec); err != nil {
		return fmt.Errorf("validate wire v2 visitor response")
	}
	if err := validateMessageSequence(bytes.NewReader(files["wire/v2/start-work-conn.bin"]), wire.ProtocolV2, []string{"start-work"}, spec); err != nil {
		return fmt.Errorf("validate wire v2 start work connection")
	}
	if err := validateMessageSequence(bytes.NewReader(files["control/v1/plain/client-to-server.bin"]), wire.ProtocolV1, spec.clientMessages, spec); err != nil {
		return fmt.Errorf("validate v1 client control plaintext")
	}
	if err := validateMessageSequence(bytes.NewReader(files["control/v1/plain/server-to-client.bin"]), wire.ProtocolV1, spec.serverMessages, spec); err != nil {
		return fmt.Errorf("validate v1 server control plaintext")
	}
	if err := validateMessageSequence(bytes.NewReader(files["control/v2/plain/client-to-server.bin"]), wire.ProtocolV2, spec.clientMessages, spec); err != nil {
		return fmt.Errorf("validate v2 client control plaintext")
	}
	if err := validateMessageSequence(bytes.NewReader(files["control/v2/plain/server-to-client.bin"]), wire.ProtocolV2, spec.serverMessages, spec); err != nil {
		return fmt.Errorf("validate v2 server control plaintext")
	}

	controlPaths := make([]string, 0, len(spec.controls))
	for path := range spec.controls {
		controlPaths = append(controlPaths, path)
	}
	sort.Strings(controlPaths)
	for _, path := range controlPaths {
		vector := spec.controls[path]
		plain, err := decryptControl(bytes.NewReader(files[path]), vector, vector.readRole)
		if err != nil || !bytes.Equal(plain, files[vector.plainPath]) {
			return fmt.Errorf("validate control ciphertext %s", path)
		}
		if err := validateMessageSequence(bytes.NewReader(plain), vector.protocol, vector.messages, spec); err != nil {
			return fmt.Errorf("validate decrypted control messages %s", path)
		}
	}
	if err := validateSnappyArtifacts(files); err != nil {
		return err
	}

	yamuxPaths := make([]string, 0, len(spec.yamuxTraces))
	for path := range spec.yamuxTraces {
		yamuxPaths = append(yamuxPaths, path)
	}
	sort.Strings(yamuxPaths)
	for _, path := range yamuxPaths {
		frames, err := parseYamuxTrace(bytes.NewReader(files[path]))
		if err != nil || !equalYamuxFrames(frames, spec.yamuxTraces[path]) {
			return fmt.Errorf("validate yamux trace %s", path)
		}
	}
	if err := validateYamuxCoverage(spec.yamuxTraces); err != nil {
		return err
	}
	if err := validateChunkPlans(files, spec, manifestValue.ChunkPlans); err != nil {
		return err
	}
	if err := validateMutations(files, spec, manifestValue.Mutations); err != nil {
		return err
	}
	return nil
}

func validateMessageSequence(reader io.Reader, protocol string, expected []string, spec *oracleSpec) error {
	rw := msg.NewReadWriter(&readWriter{Reader: reader, Writer: io.Discard}, protocol)
	for _, kind := range expected {
		decoded, err := rw.ReadMsg()
		if err != nil {
			return err
		}
		if err := validateDecodedMessage(kind, decoded, spec); err != nil {
			return err
		}
	}
	return expectEOF(reader)
}

func validateDecodedMessage(kind string, decoded msg.Message, spec *oracleSpec) error {
	expected, err := expectedDecodedMessage(kind, spec)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(decoded, expected) {
		return fmt.Errorf("decoded message differs from the oracle")
	}
	switch value := decoded.(type) {
	case *msg.Login:
		if auth.NewTokenAuth(nil, syntheticToken).VerifyLogin(value) != nil {
			return fmt.Errorf("decoded login token authentication failed")
		}
	case *msg.Ping:
		if auth.NewTokenAuth([]v1.AuthScope{v1.AuthScopeHeartBeats}, syntheticToken).VerifyPing(value) != nil {
			return fmt.Errorf("decoded heartbeat token authentication failed")
		}
	case *msg.NewWorkConn:
		if auth.NewTokenAuth([]v1.AuthScope{v1.AuthScopeNewWorkConns}, syntheticToken).VerifyNewWorkConn(value) != nil {
			return fmt.Errorf("decoded work connection token authentication failed")
		}
	case *msg.NewVisitorConn:
		if util.GetAuthKey(syntheticSTCPSecret, value.Timestamp) != value.SignKey {
			return fmt.Errorf("decoded STCP authentication failed")
		}
	}
	return nil
}

func expectedDecodedMessage(kind string, spec *oracleSpec) (msg.Message, error) {
	switch kind {
	case "login":
		return spec.login, nil
	case "login-response":
		return spec.loginResponse, nil
	case "visitor":
		return spec.visitor, nil
	case "visitor-response":
		return spec.visitorResponse, nil
	case "ping":
		return spec.ping, nil
	case "new-work":
		return spec.newWork, nil
	case "start-work":
		return spec.startWork, nil
	case "pong":
		return spec.pong, nil
	case "req-work":
		return spec.reqWork, nil
	default:
		return nil, fmt.Errorf("unknown expected message kind")
	}
}

func validateMessageSequenceWithoutAuth(reader io.Reader, protocol string, expected []string, spec *oracleSpec) error {
	rw := msg.NewReadWriter(&readWriter{Reader: reader, Writer: io.Discard}, protocol)
	for _, kind := range expected {
		decoded, err := rw.ReadMsg()
		if err != nil {
			return err
		}
		expectedMessage, err := expectedDecodedMessage(kind, spec)
		if err != nil || !reflect.DeepEqual(decoded, expectedMessage) {
			return fmt.Errorf("decoded altered message differs from the recipe")
		}
	}
	return expectEOF(reader)
}

func validateClientBootstrap(reader io.Reader, profile *cryptoProfile, spec *oracleSpec) error {
	magic := make([]byte, len(wire.MagicV2))
	if _, err := io.ReadFull(reader, magic); err != nil || !bytes.Equal(magic, []byte(wire.MagicV2)) {
		return fmt.Errorf("v2 magic mismatch")
	}
	wireConn := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard})
	frame, err := wireConn.ReadFrame()
	if err != nil || frame.Type != wire.FrameTypeClientHello {
		return fmt.Errorf("client hello frame is invalid")
	}
	var hello wire.ClientHello
	if wireConn.UnmarshalFrame(frame, &hello) != nil || !reflect.DeepEqual(hello, profile.clientHello) {
		return fmt.Errorf("client hello payload differs from the oracle")
	}
	decoded, err := msg.NewV2ReadWriterWithConn(wireConn).ReadMsg()
	if err != nil || validateDecodedMessage("login", decoded, spec) != nil {
		return fmt.Errorf("v2 login frame is invalid")
	}
	return expectEOF(reader)
}

func validateServerBootstrap(reader io.Reader, profile *cryptoProfile, spec *oracleSpec) error {
	wireConn := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard})
	frame, err := wireConn.ReadFrame()
	if err != nil || frame.Type != wire.FrameTypeServerHello {
		return fmt.Errorf("server hello frame is invalid")
	}
	var hello wire.ServerHello
	if wireConn.UnmarshalFrame(frame, &hello) != nil || !reflect.DeepEqual(hello, profile.serverHello) {
		return fmt.Errorf("server hello payload differs from the oracle")
	}
	decoded, err := msg.NewV2ReadWriterWithConn(wireConn).ReadMsg()
	if err != nil || validateDecodedMessage("login-response", decoded, spec) != nil {
		return fmt.Errorf("v2 login response frame is invalid")
	}
	return expectEOF(reader)
}

func validateV2Visitor(reader io.Reader, spec *oracleSpec) error {
	magic := make([]byte, len(wire.MagicV2))
	if _, err := io.ReadFull(reader, magic); err != nil || !bytes.Equal(magic, []byte(wire.MagicV2)) {
		return fmt.Errorf("v2 visitor magic mismatch")
	}
	return validateMessageSequence(reader, wire.ProtocolV2, []string{"visitor"}, spec)
}

func decryptControl(reader io.Reader, vector *controlVector, role netpkg.AEADCryptoRole) ([]byte, error) {
	if vector.protocol == wire.ProtocolV1 {
		var plaintext []byte
		err := withFRPCryptoSalt(func() error {
			var err error
			plaintext, err = readBounded(libcrypto.NewReader(reader, []byte(syntheticToken)), maxCanonicalFileSize)
			return err
		})
		return plaintext, err
	}
	dummyNonce := make([]byte, len(vector.profile.clientStreamNonce))
	var plaintext []byte
	err := withFixedCryptoRand(dummyNonce, func() error {
		cryptoRW, err := netpkg.NewAEADCryptoReadWriter(
			&readWriter{Reader: reader, Writer: io.Discard},
			[]byte(syntheticToken),
			role,
			vector.algorithm,
			vector.profile.transcriptHash,
		)
		if err != nil {
			return err
		}
		plaintext, err = readBounded(cryptoRW, maxCanonicalFileSize)
		return err
	})
	return plaintext, err
}

func withFRPCryptoSalt(operation func() error) error {
	cryptoRandMutex.Lock()
	defer cryptoRandMutex.Unlock()
	previous := libcrypto.DefaultSalt
	libcrypto.DefaultSalt = "frp"
	defer func() { libcrypto.DefaultSalt = previous }()
	return operation()
}

func readBounded(reader io.Reader, maximum int64) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(reader, maximum+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > maximum {
		return nil, fmt.Errorf("decoded fixture exceeds the validation limit")
	}
	return data, nil
}

func parseYamuxTrace(reader io.Reader) ([]yamuxFrame, error) {
	frames := make([]yamuxFrame, 0, 10)
	for {
		header := make([]byte, yamuxHeaderSize)
		_, err := io.ReadFull(reader, header)
		if err == io.EOF {
			return frames, nil
		}
		if err != nil {
			return nil, err
		}
		if header[0] != yamuxVersion || header[1] > yamuxTypeGoAway {
			return nil, fmt.Errorf("invalid yamux header")
		}
		frame := yamuxFrame{
			messageType: header[1],
			flags:       binary.BigEndian.Uint16(header[2:4]),
			streamID:    binary.BigEndian.Uint32(header[4:8]),
			length:      binary.BigEndian.Uint32(header[8:12]),
		}
		if frame.flags&^(yamuxFlagSYN|yamuxFlagACK|yamuxFlagFIN|yamuxFlagRST) != 0 {
			return nil, fmt.Errorf("invalid yamux flags")
		}
		if frame.messageType == yamuxTypePing || frame.messageType == yamuxTypeGoAway {
			if frame.streamID != 0 {
				return nil, fmt.Errorf("invalid yamux session frame stream ID")
			}
		} else if frame.streamID == 0 {
			return nil, fmt.Errorf("invalid yamux stream frame ID")
		}
		if frame.messageType == yamuxTypeData {
			if frame.length > maxCanonicalFileSize {
				return nil, fmt.Errorf("yamux data payload exceeds the validation limit")
			}
			frame.payload = make([]byte, frame.length)
			if _, err := io.ReadFull(reader, frame.payload); err != nil {
				return nil, err
			}
		}
		frames = append(frames, frame)
	}
}

func firstYamuxDataPayloadOffset(data []byte) (int, error) {
	position := 0
	for position < len(data) {
		if len(data)-position < yamuxHeaderSize {
			return 0, fmt.Errorf("yamux trace has a truncated header")
		}
		header := data[position : position+yamuxHeaderSize]
		if header[0] != yamuxVersion || header[1] > yamuxTypeGoAway {
			return 0, fmt.Errorf("yamux trace has an invalid header")
		}
		length := binary.BigEndian.Uint32(header[8:12])
		position += yamuxHeaderSize
		if header[1] != yamuxTypeData {
			continue
		}
		if uint64(length) > uint64(len(data)-position) {
			return 0, fmt.Errorf("yamux trace has a truncated data frame")
		}
		if length > 0 {
			return position, nil
		}
		position += int(length)
	}
	return 0, fmt.Errorf("yamux trace has no data payload")
}

func equalYamuxFrames(left, right []yamuxFrame) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index].messageType != right[index].messageType || left[index].flags != right[index].flags || left[index].streamID != right[index].streamID || left[index].length != right[index].length || !bytes.Equal(left[index].payload, right[index].payload) {
			return false
		}
	}
	return true
}

func validateChunkPlans(files map[string][]byte, spec *oracleSpec, plans []chunkPlan) error {
	for _, plan := range plans {
		reader := &chunkReader{reader: bytes.NewReader(files[plan.Source]), sizes: plan.Sizes}
		var err error
		switch plan.ID {
		case "wire-v1-bytewise":
			err = validateMessageSequence(reader, wire.ProtocolV1, []string{"login"}, spec)
		case "wire-v2-frame-splits":
			err = validateClientBootstrap(reader, spec.profiles["aes-256-gcm"], spec)
		case "control-v2-aes-record-splits":
			vector := spec.controls[plan.Source]
			var plaintext []byte
			plaintext, err = decryptControl(reader, vector, vector.readRole)
			if err == nil && !bytes.Equal(plaintext, vector.plain) {
				err = fmt.Errorf("chunked AEAD plaintext differs")
			}
		case "snappy-cfb-framed-splits":
			err = validateSnappyCFBChunkPlan(reader)
		case "yamux-flow-control-splits", "yamux-lifecycle-splits", "yamux-reset-splits":
			var frames []yamuxFrame
			frames, err = parseYamuxTrace(reader)
			if err == nil && !equalYamuxFrames(frames, spec.yamuxTraces[plan.Source]) {
				err = fmt.Errorf("chunked yamux trace differs")
			}
		default:
			return fmt.Errorf("unknown chunk plan %s", plan.ID)
		}
		if err != nil {
			return fmt.Errorf("chunk plan %s failed", plan.ID)
		}
	}
	return nil
}

func (reader *chunkReader) Read(buffer []byte) (int, error) {
	if len(buffer) == 0 {
		return 0, nil
	}
	size := reader.sizes[reader.index%len(reader.sizes)]
	reader.index++
	if size < len(buffer) {
		buffer = buffer[:size]
	}
	return reader.reader.Read(buffer)
}

func validateMutations(files map[string][]byte, spec *oracleSpec, recipes []mutation) error {
	for _, recipe := range recipes {
		source := files[recipe.Source]
		mutated, err := applyMutation(source, recipe, spec)
		if err != nil {
			return fmt.Errorf("mutation %s is invalid", recipe.ID)
		}
		if err := validateMutationOutcome(recipe, source, mutated, spec); err != nil {
			return fmt.Errorf("mutation %s did not produce %s", recipe.ID, recipe.Expected)
		}
	}
	return nil
}

func applyMutation(source []byte, recipe mutation, spec *oracleSpec) ([]byte, error) {
	if recipe.Operation == "wrong-aead-role" {
		return append([]byte(nil), source...), nil
	}
	if recipe.Operation == "swap-aead-records" {
		vector := spec.controls[recipe.Source]
		if vector == nil {
			return nil, fmt.Errorf("missing control vector")
		}
		return swapFirstAEADRecords(source, vector.algorithm)
	}
	mutated := append([]byte(nil), source...)
	switch recipe.Operation {
	case "xor-byte":
		if recipe.Offset == nil || recipe.Mask == nil || *recipe.Offset < 0 || *recipe.Offset >= len(mutated) {
			return nil, fmt.Errorf("invalid xor mutation")
		}
		mutated[*recipe.Offset] ^= *recipe.Mask
	case "set-byte":
		if recipe.Offset == nil || recipe.ByteValue == nil || *recipe.Offset < 0 || *recipe.Offset >= len(mutated) {
			return nil, fmt.Errorf("invalid byte mutation")
		}
		mutated[*recipe.Offset] = *recipe.ByteValue
	case "set-int64-be":
		if recipe.Offset == nil || recipe.IntValue == nil || *recipe.Offset < 0 || *recipe.Offset+8 > len(mutated) {
			return nil, fmt.Errorf("invalid int64 mutation")
		}
		binary.BigEndian.PutUint64(mutated[*recipe.Offset:*recipe.Offset+8], uint64(*recipe.IntValue))
	case "set-uint16-be":
		if recipe.Offset == nil || recipe.IntValue == nil || *recipe.IntValue < 0 || *recipe.IntValue > 0xffff || *recipe.Offset < 0 || *recipe.Offset+2 > len(mutated) {
			return nil, fmt.Errorf("invalid uint16 mutation")
		}
		binary.BigEndian.PutUint16(mutated[*recipe.Offset:*recipe.Offset+2], uint16(*recipe.IntValue))
	case "set-uint32-be":
		if recipe.Offset == nil || recipe.IntValue == nil || *recipe.IntValue < 0 || *recipe.Offset < 0 || *recipe.Offset+4 > len(mutated) {
			return nil, fmt.Errorf("invalid uint32 mutation")
		}
		binary.BigEndian.PutUint32(mutated[*recipe.Offset:*recipe.Offset+4], uint32(*recipe.IntValue))
	case "truncate-tail":
		if recipe.Count == nil || *recipe.Count <= 0 || *recipe.Count >= len(mutated) {
			return nil, fmt.Errorf("invalid truncation mutation")
		}
		mutated = mutated[:len(mutated)-*recipe.Count]
	default:
		return nil, fmt.Errorf("unknown mutation operation")
	}
	return mutated, nil
}

func validateMutationOutcome(recipe mutation, original, mutated []byte, spec *oracleSpec) error {
	switch recipe.Expected {
	case "reject-negative-length":
		_, err := msg.ReadMsg(bytes.NewReader(mutated))
		if !errors.Is(err, jsonmsg.ErrMsgLength) {
			return fmt.Errorf("negative length was not rejected")
		}
	case "reject-over-limit":
		if recipe.Source == "wire/v1/login-token.bin" {
			if err := validateV1JSONLengthLimit(mutated); err != nil {
				return err
			}
		} else if err := validateV2FramePayloadLimit(mutated); err != nil {
			return err
		}
	case "reject-flags":
		reader := bytes.NewReader(mutated[len(wire.MagicV2):])
		if _, err := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard}).ReadFrame(); err == nil {
			return fmt.Errorf("v2 flags were not rejected")
		}
	case "reject-unknown-type":
		switch recipe.Source {
		case "wire/v1/login-token.bin":
			_, err := msg.ReadMsg(bytes.NewReader(mutated))
			if !errors.Is(err, jsonmsg.ErrMsgType) {
				return fmt.Errorf("v1 type was not rejected")
			}
		case "wire/v2/new-visitor-stcp.bin":
			reader := bytes.NewReader(mutated[len(wire.MagicV2):])
			frame, err := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard}).ReadFrame()
			if err != nil {
				return err
			}
			if _, err := msg.DecodeV2MessageFrame(frame); err == nil {
				return fmt.Errorf("v2 message type was not rejected")
			}
		case yamuxLifecycleClientTracePath:
			if _, err := parseYamuxTrace(bytes.NewReader(mutated)); err == nil {
				return fmt.Errorf("yamux type was not rejected")
			}
		default:
			return fmt.Errorf("unknown rejection source")
		}
	case "reject-truncated":
		if vector := spec.controls[recipe.Source]; vector != nil {
			if _, err := decryptControl(bytes.NewReader(mutated), vector, vector.readRole); err == nil {
				return fmt.Errorf("truncated AEAD stream was accepted")
			}
		} else if recipe.Source == "wire/v1/login-token.bin" {
			if _, err := msg.ReadMsg(bytes.NewReader(mutated)); err == nil {
				return fmt.Errorf("truncated v1 frame was accepted")
			}
		} else {
			if err := validateClientBootstrap(bytes.NewReader(mutated), spec.profiles["aes-256-gcm"], spec); err == nil {
				return fmt.Errorf("truncated v2 bootstrap was accepted")
			}
		}
	case "integrity-failure":
		vector := spec.controls[recipe.Source]
		if vector == nil {
			return fmt.Errorf("missing AEAD control vector")
		}
		role := vector.readRole
		if recipe.Operation == "wrong-aead-role" {
			if role == netpkg.AEADCryptoRoleClient {
				role = netpkg.AEADCryptoRoleServer
			} else {
				role = netpkg.AEADCryptoRoleClient
			}
		}
		if _, err := decryptControl(bytes.NewReader(mutated), vector, role); err == nil {
			return fmt.Errorf("AEAD mutation was accepted")
		}
	case "accept-altered":
		if vector := spec.controls[recipe.Source]; vector != nil {
			plaintext, err := decryptControl(bytes.NewReader(mutated), vector, vector.readRole)
			if err != nil || bytes.Equal(plaintext, vector.plain) {
				return fmt.Errorf("CFB mutation was not accepted as altered plaintext")
			}
			if err := validateMessageSequenceWithoutAuth(bytes.NewReader(plaintext), vector.protocol, vector.messages, specWithAlteredFinalTimestamp(spec)); err != nil {
				return fmt.Errorf("altered CFB plaintext no longer decodes")
			}
		} else {
			frames, err := parseYamuxTrace(bytes.NewReader(mutated))
			if err != nil || equalYamuxFrames(frames, spec.yamuxTraces[recipe.Source]) || bytes.Equal(original, mutated) {
				return fmt.Errorf("yamux mutation was not accepted as altered payload")
			}
		}
	default:
		return fmt.Errorf("unknown mutation expectation")
	}
	return nil
}

func validateV1JSONLengthLimit(mutated []byte) error {
	const frameHeaderSize = 9
	if len(mutated) < frameHeaderSize {
		return fmt.Errorf("v1 frame header is incomplete")
	}
	overLimit := int64(wireV1MaxJSONLength + 1)
	if declared := int64(binary.BigEndian.Uint64(mutated[1:frameHeaderSize])); declared != overLimit {
		return fmt.Errorf("v1 max-plus-one mutation declared %d bytes", declared)
	}

	buildFrame := func(payloadLength int) []byte {
		frame := make([]byte, frameHeaderSize+payloadLength)
		frame[0] = mutated[0]
		binary.BigEndian.PutUint64(frame[1:frameHeaderSize], uint64(payloadLength))
		frame[frameHeaderSize] = '{'
		frame[frameHeaderSize+1] = '}'
		for index := frameHeaderSize + 2; index < len(frame); index++ {
			frame[index] = ' '
		}
		return frame
	}

	if _, err := msg.ReadMsg(bytes.NewReader(buildFrame(int(wireV1MaxJSONLength) + 1))); !errors.Is(err, jsonmsg.ErrMaxMsgLength) {
		return fmt.Errorf("v1 max-plus-one payload did not fail with the length limit")
	}
	if _, err := msg.ReadMsg(bytes.NewReader(buildFrame(int(wireV1MaxJSONLength)))); err != nil {
		return fmt.Errorf("v1 exact-limit payload was rejected")
	}
	return nil
}

func validateV2FramePayloadLimit(mutated []byte) error {
	const frameHeaderSize = 8
	headerStart := len(wire.MagicV2)
	headerEnd := headerStart + frameHeaderSize
	if len(mutated) < headerEnd {
		return fmt.Errorf("v2 frame header is incomplete")
	}

	header := append([]byte(nil), mutated[headerStart:headerEnd]...)
	overLimit := uint32(wire.DefaultMaxFramePayloadSize + 1)
	if declared := binary.BigEndian.Uint32(header[4:8]); declared != overLimit {
		return fmt.Errorf("v2 max-plus-one mutation declared %d bytes", declared)
	}
	overLimitFrame := make([]byte, frameHeaderSize+int(overLimit))
	copy(overLimitFrame, header)
	reader := bytes.NewReader(overLimitFrame)
	if _, err := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard}).ReadFrame(); err == nil || !strings.Contains(err.Error(), "exceeds limit") {
		return fmt.Errorf("v2 max-plus-one payload did not fail with the length limit")
	}

	binary.BigEndian.PutUint32(header[4:8], wire.DefaultMaxFramePayloadSize)
	exactLimitFrame := make([]byte, frameHeaderSize+int(wire.DefaultMaxFramePayloadSize))
	copy(exactLimitFrame, header)
	reader = bytes.NewReader(exactLimitFrame)
	frame, err := wire.NewConn(&readWriter{Reader: reader, Writer: io.Discard}).ReadFrame()
	if err != nil {
		return fmt.Errorf("v2 exact-limit payload was rejected")
	}
	if len(frame.Payload) != int(wire.DefaultMaxFramePayloadSize) {
		return fmt.Errorf("v2 exact-limit payload length changed")
	}
	return nil
}

func specWithAlteredFinalTimestamp(spec *oracleSpec) *oracleSpec {
	copyOfSpec := *spec
	copyOfWork := *spec.newWork
	copyOfWork.Timestamp ^= 1
	copyOfSpec.newWork = &copyOfWork
	return &copyOfSpec
}

func swapFirstAEADRecords(source []byte, algorithm string) ([]byte, error) {
	nonceSize := 12
	if algorithm == wire.AEADAlgorithmXChaCha20Poly1305 {
		nonceSize = 24
	}
	if len(source) < nonceSize+8 {
		return nil, fmt.Errorf("AEAD stream is too short")
	}
	records := make([][]byte, 0, 2)
	position := nonceSize
	for position < len(source) {
		if position+4 > len(source) {
			return nil, fmt.Errorf("AEAD record header is truncated")
		}
		length := int(binary.BigEndian.Uint32(source[position : position+4]))
		end := position + 4 + length
		if length < 0 || end > len(source) {
			return nil, fmt.Errorf("AEAD record is truncated")
		}
		records = append(records, append([]byte(nil), source[position:end]...))
		position = end
	}
	if len(records) < 2 {
		return nil, fmt.Errorf("AEAD stream has fewer than two records")
	}
	result := append([]byte(nil), source[:nonceSize]...)
	result = append(result, records[1]...)
	result = append(result, records[0]...)
	for _, record := range records[2:] {
		result = append(result, record...)
	}
	return result, nil
}

func expectEOF(reader io.Reader) error {
	var single [1]byte
	count, err := reader.Read(single[:])
	if count != 0 || err != io.EOF {
		return fmt.Errorf("fixture contains trailing bytes")
	}
	return nil
}
