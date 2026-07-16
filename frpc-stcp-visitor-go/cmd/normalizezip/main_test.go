package main

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNormalizeMakesOrderAndTimestampsDeterministic(t *testing.T) {
	first := filepath.Join(t.TempDir(), "first.zip")
	second := filepath.Join(t.TempDir(), "second.zip")
	writeTestZip(t, first, []string{"b.txt", "a.txt"}, time.Now())
	writeTestZip(t, second, []string{"a.txt", "b.txt"}, time.Now().Add(time.Hour))

	if err := normalize(first); err != nil {
		t.Fatal(err)
	}
	if err := normalize(second); err != nil {
		t.Fatal(err)
	}
	firstBytes, err := os.ReadFile(first)
	if err != nil {
		t.Fatal(err)
	}
	secondBytes, err := os.ReadFile(second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(firstBytes, secondBytes) {
		t.Fatal("normalized archives differ")
	}
}

func TestNormalizeWithAdditionsReplacesEntryDeterministically(t *testing.T) {
	archivePath := filepath.Join(t.TempDir(), "archive.zip")
	writeTestZip(t, archivePath, []string{"META-INF/OCDECK/NOTICE.txt", "classes.jar"}, time.Now())
	additionPath := filepath.Join(t.TempDir(), "NOTICE")
	if err := os.WriteFile(additionPath, []byte("replacement\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := normalizeWithAdditions(archivePath, map[string]string{
		"META-INF/OCDECK/NOTICE.txt": additionPath,
	}); err != nil {
		t.Fatal(err)
	}
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	for _, entry := range reader.File {
		if entry.Name != "META-INF/OCDECK/NOTICE.txt" {
			continue
		}
		input, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		var output bytes.Buffer
		if _, err := output.ReadFrom(input); err != nil {
			t.Fatal(err)
		}
		if err := input.Close(); err != nil {
			t.Fatal(err)
		}
		if got, want := output.String(), "replacement\n"; got != want {
			t.Fatalf("content = %q, want %q", got, want)
		}
		return
	}
	t.Fatal("added entry not found")
}

func TestParseArgumentsRejectsUnsafeArchivePath(t *testing.T) {
	if _, _, err := parseArguments([]string{"--add", "../secret=file", "archive.zip"}); err == nil {
		t.Fatal("expected unsafe archive path to fail")
	}
}

func writeTestZip(t *testing.T, path string, names []string, modified time.Time) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	writer := zip.NewWriter(file)
	for _, name := range names {
		header := &zip.FileHeader{Name: name, Method: zip.Deflate, Modified: modified}
		entry, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(name)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}
