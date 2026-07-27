package contractfixture

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	fmux "github.com/hashicorp/yamux"
)

const (
	yamuxLifecycleClientTracePath   = "yamux/client-to-server.trace.bin"
	yamuxLifecycleServerTracePath   = "yamux/server-to-client.trace.bin"
	yamuxFlowControlClientTracePath = "yamux/flow-control/client-to-server.trace.bin"
	yamuxFlowControlServerTracePath = "yamux/flow-control/server-to-client.trace.bin"
	yamuxResetClientTracePath       = "yamux/reset/client-to-server.trace.bin"
	yamuxResetServerTracePath       = "yamux/reset/server-to-client.trace.bin"
	yamuxScenarioTimeout            = 10 * time.Second
)

type yamuxArtifact struct {
	id       string
	path     string
	expected string
	data     []byte
	frames   []yamuxFrame
}

type yamuxRecording struct {
	clientToServer []byte
	serverToClient []byte
}

type yamuxHarness struct {
	client     *fmux.Session
	server     *fmux.Session
	clientConn *recordingConn
	serverConn *recordingConn
}

type recordingConn struct {
	conn net.Conn

	mutex        sync.Mutex
	trace        bytes.Buffer
	recordingErr error
	closed       bool
}

func buildYamuxArtifacts() ([]yamuxArtifact, error) {
	// The upstream module path is retained, while go.mod pins the fatedier fork.
	defaultConfig := fmux.DefaultConfig()
	if defaultConfig.MaxStreamWindowSize != yamuxInitialWindow {
		return nil, fmt.Errorf("pinned yamux initial stream window changed")
	}

	clientOddPayload := []byte("client-odd-data")
	serverOddPayload := []byte("server-odd-data")
	serverEvenPayload := []byte("server-even-data")
	clientEvenPayload := []byte("client-even-data")
	lifecycle, err := recordYamuxLifecycle(clientOddPayload, serverOddPayload, serverEvenPayload, clientEvenPayload)
	if err != nil {
		return nil, err
	}

	flowPayload := makeYamuxPayload(int(yamuxInitialWindow / 2))
	flowControl, err := recordYamuxFlowControl(flowPayload)
	if err != nil {
		return nil, err
	}

	reset, err := recordYamuxReset()
	if err != nil {
		return nil, err
	}

	windowDelta := yamuxFRPStreamWindow - yamuxInitialWindow
	artifacts := []yamuxArtifact{
		{
			id:       "yamux-client-to-server-trace",
			path:     yamuxLifecycleClientTracePath,
			expected: "recorded pinned Client/Server lifecycle; frames=9; odd client SYN; DATA; FIN; Ping API; GOAWAY",
			data:     lifecycle.clientToServer,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagSYN, streamID: 1, length: windowDelta},
				expectedYamuxDataFrame(1, clientOddPayload),
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagACK, streamID: 2, length: windowDelta},
				expectedYamuxDataFrame(2, clientEvenPayload),
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 2},
				{messageType: yamuxTypeGoAway},
				{messageType: yamuxTypePing, flags: yamuxFlagSYN},
				{messageType: yamuxTypePing, flags: yamuxFlagACK},
			},
		},
		{
			id:       "yamux-server-to-client-trace",
			path:     yamuxLifecycleServerTracePath,
			expected: "recorded pinned Client/Server lifecycle; frames=9; even server SYN; DATA; FIN; Ping API; GOAWAY",
			data:     lifecycle.serverToClient,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagACK, streamID: 1, length: windowDelta},
				expectedYamuxDataFrame(1, serverOddPayload),
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagSYN, streamID: 2, length: windowDelta},
				expectedYamuxDataFrame(2, serverEvenPayload),
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 2},
				{messageType: yamuxTypePing, flags: yamuxFlagACK},
				{messageType: yamuxTypeGoAway},
				{messageType: yamuxTypePing, flags: yamuxFlagSYN},
			},
		},
		{
			id:       "yamux-flow-control-client-to-server-trace",
			path:     yamuxFlowControlClientTracePath,
			expected: "recorded pinned Client/Server flow control; frames=3; payload consumes half the receive window",
			data:     flowControl.clientToServer,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagSYN, streamID: 1},
				expectedYamuxDataFrame(1, flowPayload),
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
			},
		},
		{
			id:       "yamux-flow-control-server-to-client-trace",
			path:     yamuxFlowControlServerTracePath,
			expected: "recorded pinned Client/Server flow control; frames=3; ACK; WINDOW_UPDATE; FIN",
			data:     flowControl.serverToClient,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagACK, streamID: 1},
				{messageType: yamuxTypeWindowUpdate, streamID: 1, length: uint32(len(flowPayload))},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
			},
		},
		{
			id:       "yamux-reset-client-to-server-trace",
			path:     yamuxResetClientTracePath,
			expected: "recorded pinned Client/Server backlog reset; frames=3; two SYN attempts; surviving stream FIN",
			data:     reset.clientToServer,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagSYN, streamID: 1},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagSYN, streamID: 3},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
			},
		},
		{
			id:       "yamux-reset-server-to-client-trace",
			path:     yamuxResetServerTracePath,
			expected: "recorded pinned Client/Server backlog reset; frames=3; RST error; surviving stream ACK and FIN",
			data:     reset.serverToClient,
			frames: []yamuxFrame{
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagRST, streamID: 3},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagACK, streamID: 1},
				{messageType: yamuxTypeWindowUpdate, flags: yamuxFlagFIN, streamID: 1},
			},
		},
	}

	traces := make(map[string][]yamuxFrame, len(artifacts))
	for _, artifact := range artifacts {
		frames, err := parseYamuxTrace(bytes.NewReader(artifact.data))
		if err != nil {
			return nil, fmt.Errorf("parse recorded yamux trace %s: %w", artifact.path, err)
		}
		if !equalYamuxFrames(frames, artifact.frames) {
			return nil, fmt.Errorf("recorded yamux trace %s differs from the pinned API scenario", artifact.path)
		}
		traces[artifact.path] = artifact.frames
	}
	if err := validateYamuxCoverage(traces); err != nil {
		return nil, err
	}
	return artifacts, nil
}

func recordYamuxLifecycle(clientOddPayload, serverOddPayload, serverEvenPayload, clientEvenPayload []byte) (yamuxRecording, error) {
	config := yamuxFixtureConfig(yamuxFRPStreamWindow, 4)
	return runYamuxScenario(config, config.Clone(), func(harness *yamuxHarness) error {
		clientStream, err := harness.client.OpenStream()
		if err != nil {
			return fmt.Errorf("open client yamux stream: %w", err)
		}
		serverStream, err := harness.server.AcceptStream()
		if err != nil {
			return fmt.Errorf("accept client yamux stream: %w", err)
		}
		if clientStream.StreamID() != 1 || serverStream.StreamID() != 1 {
			return fmt.Errorf("client yamux stream ID changed")
		}
		if err := exchangeYamuxPayload(clientStream, serverStream, clientOddPayload); err != nil {
			return fmt.Errorf("exchange client payload: %w", err)
		}
		if err := exchangeYamuxPayload(serverStream, clientStream, serverOddPayload); err != nil {
			return fmt.Errorf("exchange server payload: %w", err)
		}
		if err := closeYamuxStreamPair(clientStream, serverStream); err != nil {
			return fmt.Errorf("close client yamux stream: %w", err)
		}

		serverStream, err = harness.server.OpenStream()
		if err != nil {
			return fmt.Errorf("open server yamux stream: %w", err)
		}
		clientStream, err = harness.client.AcceptStream()
		if err != nil {
			return fmt.Errorf("accept server yamux stream: %w", err)
		}
		if clientStream.StreamID() != 2 || serverStream.StreamID() != 2 {
			return fmt.Errorf("server yamux stream ID changed")
		}
		if err := exchangeYamuxPayload(serverStream, clientStream, serverEvenPayload); err != nil {
			return fmt.Errorf("exchange even server payload: %w", err)
		}
		if err := exchangeYamuxPayload(clientStream, serverStream, clientEvenPayload); err != nil {
			return fmt.Errorf("exchange even client payload: %w", err)
		}
		if err := closeYamuxStreamPair(serverStream, clientStream); err != nil {
			return fmt.Errorf("close server yamux stream: %w", err)
		}
		if err := requireNoYamuxStreams(harness); err != nil {
			return err
		}

		if err := harness.client.GoAway(); err != nil {
			return fmt.Errorf("send client yamux go away: %w", err)
		}
		if err := pingYamuxBarrier(harness.client); err != nil {
			return fmt.Errorf("client yamux Ping API failed: %w", err)
		}
		if stream, err := harness.server.OpenStream(); !errors.Is(err, fmux.ErrRemoteGoAway) {
			if stream != nil {
				_ = stream.Close()
			}
			return fmt.Errorf("server did not observe client yamux go away")
		}

		if err := harness.server.GoAway(); err != nil {
			return fmt.Errorf("send server yamux go away: %w", err)
		}
		if err := pingYamuxBarrier(harness.server); err != nil {
			return fmt.Errorf("server yamux Ping API failed: %w", err)
		}
		if stream, err := harness.client.OpenStream(); !errors.Is(err, fmux.ErrRemoteGoAway) {
			if stream != nil {
				_ = stream.Close()
			}
			return fmt.Errorf("client did not observe server yamux go away")
		}
		return nil
	})
}

func recordYamuxFlowControl(payload []byte) (yamuxRecording, error) {
	config := yamuxFixtureConfig(yamuxInitialWindow, 2)
	return runYamuxScenario(config, config.Clone(), func(harness *yamuxHarness) error {
		clientStream, err := harness.client.OpenStream()
		if err != nil {
			return fmt.Errorf("open flow-control yamux stream: %w", err)
		}
		serverStream, err := harness.server.AcceptStream()
		if err != nil {
			return fmt.Errorf("accept flow-control yamux stream: %w", err)
		}
		if err := exchangeYamuxPayload(clientStream, serverStream, payload); err != nil {
			return fmt.Errorf("exchange flow-control payload: %w", err)
		}
		if err := closeYamuxStreamPair(clientStream, serverStream); err != nil {
			return fmt.Errorf("close flow-control yamux stream: %w", err)
		}
		return requireNoYamuxStreams(harness)
	})
}

func recordYamuxReset() (yamuxRecording, error) {
	clientConfig := yamuxFixtureConfig(yamuxInitialWindow, 2)
	serverConfig := yamuxFixtureConfig(yamuxInitialWindow, 1)
	return runYamuxScenario(clientConfig, serverConfig, func(harness *yamuxHarness) error {
		survivingStream, err := harness.client.OpenStream()
		if err != nil {
			return fmt.Errorf("open surviving yamux stream: %w", err)
		}
		resetStream, err := harness.client.OpenStream()
		if err != nil {
			return fmt.Errorf("open reset yamux stream: %w", err)
		}
		var single [1]byte
		if count, readErr := resetStream.Read(single[:]); count != 0 || !errors.Is(readErr, fmux.ErrConnectionReset) {
			return fmt.Errorf("yamux reset stream did not return ErrConnectionReset")
		}
		if err := resetStream.Close(); err != nil {
			return fmt.Errorf("close reset yamux stream: %w", err)
		}

		acceptedStream, err := harness.server.AcceptStream()
		if err != nil {
			return fmt.Errorf("accept surviving yamux stream: %w", err)
		}
		if survivingStream.StreamID() != 1 || acceptedStream.StreamID() != 1 {
			return fmt.Errorf("surviving yamux stream ID changed")
		}
		if err := closeYamuxStreamPair(survivingStream, acceptedStream); err != nil {
			return fmt.Errorf("close surviving yamux stream: %w", err)
		}
		return requireNoYamuxStreams(harness)
	})
}

func runYamuxScenario(clientConfig, serverConfig *fmux.Config, scenario func(*yamuxHarness) error) (yamuxRecording, error) {
	harness, err := newYamuxHarness(clientConfig, serverConfig)
	if err != nil {
		return yamuxRecording{}, err
	}
	scenarioErr := scenario(harness)
	closeErr := harness.close()
	recording, recordingErr := harness.recording()
	if err := errors.Join(scenarioErr, closeErr, recordingErr); err != nil {
		return yamuxRecording{}, err
	}
	return recording, nil
}

func newYamuxHarness(clientConfig, serverConfig *fmux.Config) (*yamuxHarness, error) {
	clientRaw, serverRaw := net.Pipe()
	deadline := time.Now().Add(yamuxScenarioTimeout)
	if err := clientRaw.SetDeadline(deadline); err != nil {
		_ = clientRaw.Close()
		_ = serverRaw.Close()
		return nil, fmt.Errorf("set pinned yamux client deadline: %w", err)
	}
	if err := serverRaw.SetDeadline(deadline); err != nil {
		_ = clientRaw.Close()
		_ = serverRaw.Close()
		return nil, fmt.Errorf("set pinned yamux server deadline: %w", err)
	}
	clientConn := &recordingConn{conn: clientRaw}
	serverConn := &recordingConn{conn: serverRaw}
	client, err := fmux.Client(clientConn, clientConfig)
	if err != nil {
		_ = clientConn.Close()
		_ = serverConn.Close()
		return nil, fmt.Errorf("create pinned yamux client: %w", err)
	}
	server, err := fmux.Server(serverConn, serverConfig)
	if err != nil {
		_ = client.Close()
		_ = serverConn.Close()
		return nil, fmt.Errorf("create pinned yamux server: %w", err)
	}
	return &yamuxHarness{client: client, server: server, clientConn: clientConn, serverConn: serverConn}, nil
}

func yamuxFixtureConfig(window uint32, backlog int) *fmux.Config {
	config := fmux.DefaultConfig()
	config.AcceptBacklog = backlog
	config.EnableKeepAlive = false
	config.MaxStreamWindowSize = window
	config.StreamOpenTimeout = 0
	config.StreamCloseTimeout = 0
	config.LogOutput = io.Discard
	config.Logger = nil
	return config
}

func (harness *yamuxHarness) close() error {
	clientErr := harness.client.Close()
	serverErr := harness.server.Close()
	if err := errors.Join(clientErr, serverErr); err != nil {
		return fmt.Errorf("close pinned yamux sessions: %w", err)
	}
	if !harness.client.IsClosed() || !harness.server.IsClosed() {
		return fmt.Errorf("pinned yamux session did not close")
	}
	if !harness.clientConn.isClosed() || !harness.serverConn.isClosed() {
		return fmt.Errorf("pinned yamux in-memory connection did not close")
	}
	select {
	case <-harness.client.CloseChan():
	default:
		return fmt.Errorf("pinned yamux client goroutines did not stop")
	}
	select {
	case <-harness.server.CloseChan():
	default:
		return fmt.Errorf("pinned yamux server goroutines did not stop")
	}
	return nil
}

func (harness *yamuxHarness) recording() (yamuxRecording, error) {
	clientToServer, clientErr := harness.clientConn.recorded()
	serverToClient, serverErr := harness.serverConn.recorded()
	return yamuxRecording{
		clientToServer: clientToServer,
		serverToClient: serverToClient,
	}, errors.Join(clientErr, serverErr)
}

func (connection *recordingConn) Read(buffer []byte) (int, error) {
	return connection.conn.Read(buffer)
}

func (connection *recordingConn) Write(data []byte) (int, error) {
	written, err := connection.conn.Write(data)
	if written > 0 {
		connection.mutex.Lock()
		if connection.recordingErr == nil {
			if written > maxCanonicalFileSize-connection.trace.Len() {
				connection.recordingErr = fmt.Errorf("yamux trace exceeds the canonical file limit")
			} else {
				_, _ = connection.trace.Write(data[:written])
			}
		}
		recordingErr := connection.recordingErr
		connection.mutex.Unlock()
		if recordingErr != nil {
			closeErr := connection.Close()
			return written, errors.Join(err, recordingErr, closeErr)
		}
	}
	return written, err
}

func (connection *recordingConn) Close() error {
	err := connection.conn.Close()
	connection.mutex.Lock()
	connection.closed = true
	connection.mutex.Unlock()
	return err
}

func (connection *recordingConn) recorded() ([]byte, error) {
	connection.mutex.Lock()
	defer connection.mutex.Unlock()
	return append([]byte(nil), connection.trace.Bytes()...), connection.recordingErr
}

func (connection *recordingConn) isClosed() bool {
	connection.mutex.Lock()
	defer connection.mutex.Unlock()
	return connection.closed
}

func exchangeYamuxPayload(writer, reader *fmux.Stream, expected []byte) error {
	written, err := writer.Write(expected)
	if err != nil {
		return err
	}
	if written != len(expected) {
		return io.ErrShortWrite
	}
	actual := make([]byte, len(expected))
	if _, err := io.ReadFull(reader, actual); err != nil {
		return err
	}
	if !bytes.Equal(actual, expected) {
		return fmt.Errorf("yamux payload differs")
	}
	return nil
}

func closeYamuxStreamPair(first, second *fmux.Stream) error {
	if err := first.Close(); err != nil {
		return err
	}
	if err := expectYamuxEOF(second); err != nil {
		return err
	}
	if err := second.Close(); err != nil {
		return err
	}
	return expectYamuxEOF(first)
}

func expectYamuxEOF(stream *fmux.Stream) error {
	var single [1]byte
	count, err := stream.Read(single[:])
	if count != 0 || !errors.Is(err, io.EOF) {
		return fmt.Errorf("yamux stream did not reach EOF")
	}
	return nil
}

func pingYamuxBarrier(session *fmux.Session) error {
	_, err := session.Ping()
	return err
}

func requireNoYamuxStreams(harness *yamuxHarness) error {
	if harness.client.NumStreams() != 0 || harness.server.NumStreams() != 0 {
		return fmt.Errorf("yamux scenario left an open stream")
	}
	return nil
}

func expectedYamuxDataFrame(streamID uint32, payload []byte) yamuxFrame {
	return yamuxFrame{
		messageType: yamuxTypeData,
		streamID:    streamID,
		length:      uint32(len(payload)),
		payload:     append([]byte(nil), payload...),
	}
}

func makeYamuxPayload(size int) []byte {
	payload := make([]byte, size)
	for index := range payload {
		payload[index] = byte('a' + index%26)
	}
	return payload
}

func validateYamuxCoverage(traces map[string][]yamuxFrame) error {
	var data, windowUpdate, plainWindowUpdate, streamSYN, streamACK, fin, reset, pingSYN, pingACK, goAway bool
	for path, frames := range traces {
		clientToServer := strings.HasSuffix(path, "/client-to-server.trace.bin")
		serverToClient := strings.HasSuffix(path, "/server-to-client.trace.bin")
		if !clientToServer && !serverToClient {
			return fmt.Errorf("yamux trace direction is invalid")
		}
		for _, frame := range frames {
			switch frame.messageType {
			case yamuxTypeData:
				data = true
			case yamuxTypeWindowUpdate:
				windowUpdate = true
				plainWindowUpdate = plainWindowUpdate || frame.flags == 0
			case yamuxTypePing:
				pingSYN = pingSYN || frame.flags&yamuxFlagSYN != 0
				pingACK = pingACK || frame.flags&yamuxFlagACK != 0
			case yamuxTypeGoAway:
				goAway = true
			}
			streamSYN = streamSYN || frame.streamID != 0 && frame.flags&yamuxFlagSYN != 0
			streamACK = streamACK || frame.streamID != 0 && frame.flags&yamuxFlagACK != 0
			fin = fin || frame.flags&yamuxFlagFIN != 0
			reset = reset || frame.flags&yamuxFlagRST != 0
			if frame.streamID != 0 && frame.flags&yamuxFlagSYN != 0 {
				if clientToServer && frame.streamID%2 == 0 {
					return fmt.Errorf("client yamux SYN stream ID is not odd")
				}
				if serverToClient && frame.streamID%2 != 0 {
					return fmt.Errorf("server yamux SYN stream ID is not even")
				}
			}
		}
	}
	if !data || !windowUpdate || !plainWindowUpdate || !streamSYN || !streamACK || !fin || !reset || !pingSYN || !pingACK || !goAway {
		return fmt.Errorf("recorded yamux scenarios do not cover DATA, WINDOW_UPDATE, SYN, ACK, FIN, RST, PING, and GOAWAY")
	}
	return nil
}
