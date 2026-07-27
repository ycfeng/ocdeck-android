package contractfixture

import (
	"bytes"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestGeneratedFixtureSetIsDeterministicAndSelfValidating(t *testing.T) {
	root, err := repositoryRoot()
	if err != nil {
		t.Fatal(err)
	}
	first, err := generateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}
	second, err := generateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := compareFileSets(first.files, second.files); err != nil {
		t.Fatal(err)
	}
	if err := validateFixtureSet(first.files, first.manifest, first.spec); err != nil {
		t.Fatal(err)
	}
	if first.spec.visitor.UseEncryption || first.spec.visitor.UseCompression {
		t.Fatal("STCP visitor fixture enables encryption or compression")
	}
	if err := validateYamuxCoverage(first.spec.yamuxTraces); err != nil {
		t.Fatal(err)
	}
}

func TestFileSetComparisonRejectsExtraAndStaleFilesWithoutPayloads(t *testing.T) {
	root, err := repositoryRoot()
	if err != nil {
		t.Fatal(err)
	}
	generated, err := generateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}
	temporary := t.TempDir()
	if err := writeFixtureDirectory(temporary, generated.files); err != nil {
		t.Fatal(err)
	}
	actual, err := readFixtureDirectory(temporary)
	if err != nil {
		t.Fatal(err)
	}
	if err := compareFileSets(generated.files, actual); err != nil {
		t.Fatal(err)
	}

	extraPath := filepath.Join(temporary, "extra.bin")
	if err := os.WriteFile(extraPath, []byte("synthetic-extra"), 0o600); err != nil {
		t.Fatal(err)
	}
	actual, err = readFixtureDirectory(temporary)
	if err != nil {
		t.Fatal(err)
	}
	if err := compareFileSets(generated.files, actual); err == nil {
		t.Fatal("extra fixture was accepted")
	}
	if err := os.Remove(extraPath); err != nil {
		t.Fatal(err)
	}

	path := generated.manifest.Entries[0].Path
	data := append([]byte(nil), generated.files[path]...)
	data[len(data)-1] ^= 1
	if err := os.WriteFile(filepath.Join(temporary, filepath.FromSlash(path)), data, 0o600); err != nil {
		t.Fatal(err)
	}
	actual, err = readFixtureDirectory(temporary)
	if err != nil {
		t.Fatal(err)
	}
	comparisonErr := compareFileSets(generated.files, actual)
	if comparisonErr == nil {
		t.Fatal("stale fixture was accepted")
	}
	message := comparisonErr.Error()
	if strings.Contains(message, syntheticToken) || strings.Contains(message, syntheticSTCPSecret) || bytes.Contains([]byte(message), generated.files[path]) {
		t.Fatal("comparison error exposed fixture payload or synthetic credentials")
	}
}

func TestYamuxRecorderRejectsOversizedTraceBeforeBufferingIt(t *testing.T) {
	writer, reader := net.Pipe()
	if err := writer.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	readerDone := make(chan error, 1)
	go func() {
		_, err := io.Copy(io.Discard, reader)
		readerDone <- err
	}()

	connection := &recordingConn{conn: writer}
	payload := make([]byte, maxCanonicalFileSize+1)
	written, err := connection.Write(payload)
	if written != len(payload) {
		t.Fatalf("oversized write length = %d, want %d", written, len(payload))
	}
	if err == nil || !strings.Contains(err.Error(), "canonical file limit") {
		t.Fatalf("oversized write error = %v", err)
	}
	recorded, recordingErr := connection.recorded()
	if recordingErr == nil || !strings.Contains(recordingErr.Error(), "canonical file limit") {
		t.Fatalf("recording error = %v", recordingErr)
	}
	if len(recorded) != 0 {
		t.Fatalf("oversized trace buffered %d bytes", len(recorded))
	}
	if err := <-readerDone; err != nil {
		t.Fatal(err)
	}
	if err := reader.Close(); err != nil {
		t.Fatal(err)
	}
}
