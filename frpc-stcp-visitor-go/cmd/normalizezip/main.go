package main

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"time"
)

var normalizedTime = time.Date(1980, time.January, 1, 0, 0, 0, 0, time.UTC)

type zipEntry struct {
	header zip.FileHeader
	data   []byte
}

type addition struct {
	sourcePath    string
	normalizeText bool
}

func main() {
	additions, paths, err := parseArguments(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	if len(paths) == 0 {
		fmt.Fprintln(os.Stderr, "usage: normalizezip [--add|--add-text <archive-path>=<source-file>] <zip> [<zip> ...]")
		os.Exit(2)
	}
	if len(additions) > 0 && len(paths) != 1 {
		fmt.Fprintln(os.Stderr, "--add requires exactly one target archive")
		os.Exit(2)
	}
	for _, path := range paths {
		if err := normalizeWithAdditions(path, additions); err != nil {
			fmt.Fprintf(os.Stderr, "normalize %s: %v\n", path, err)
			os.Exit(1)
		}
	}
}

func normalize(path string) error {
	return normalizeWithAdditions(path, nil)
}

func normalizeWithAdditions(path string, additions map[string]addition) error {
	reader, err := zip.OpenReader(path)
	if err != nil {
		return err
	}
	entries := make(map[string]zipEntry, len(reader.File)+len(additions))
	for _, file := range reader.File {
		if _, exists := entries[file.Name]; exists {
			reader.Close()
			return fmt.Errorf("duplicate ZIP entry %q", file.Name)
		}
		input, err := file.Open()
		if err != nil {
			reader.Close()
			return err
		}
		data, readErr := io.ReadAll(input)
		closeErr := input.Close()
		if readErr != nil {
			reader.Close()
			return readErr
		}
		if closeErr != nil {
			reader.Close()
			return closeErr
		}
		header := zip.FileHeader{
			Name:     file.Name,
			Method:   file.Method,
			NonUTF8:  file.NonUTF8,
			Modified: normalizedTime,
		}
		header.SetMode(file.Mode())
		entries[file.Name] = zipEntry{header: header, data: data}
	}
	if err := reader.Close(); err != nil {
		return err
	}
	for archivePath, addition := range additions {
		data, err := os.ReadFile(addition.sourcePath)
		if err != nil {
			return fmt.Errorf("read addition %s: %w", addition.sourcePath, err)
		}
		if addition.normalizeText {
			data = normalizeTextLineEndings(data)
		}
		header := zip.FileHeader{Name: archivePath, Method: zip.Deflate, Modified: normalizedTime}
		header.SetMode(0o644)
		entries[archivePath] = zipEntry{header: header, data: data}
	}
	names := make([]string, 0, len(entries))
	for name := range entries {
		names = append(names, name)
	}
	sort.Strings(names)

	var output bytes.Buffer
	writer := zip.NewWriter(&output)
	for _, name := range names {
		entry := entries[name]
		file, err := writer.CreateHeader(&entry.header)
		if err != nil {
			return err
		}
		if _, err := file.Write(entry.data); err != nil {
			return err
		}
	}
	if err := writer.Close(); err != nil {
		return err
	}
	return os.WriteFile(path, output.Bytes(), 0o644)
}

func parseArguments(arguments []string) (map[string]addition, []string, error) {
	additions := make(map[string]addition)
	paths := make([]string, 0, len(arguments))
	for index := 0; index < len(arguments); index++ {
		flag := arguments[index]
		if flag != "--add" && flag != "--add-text" {
			paths = append(paths, arguments[index])
			continue
		}
		index++
		if index >= len(arguments) {
			return nil, nil, errors.New("--add requires <archive-path>=<source-file>")
		}
		parts := strings.SplitN(arguments[index], "=", 2)
		if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
			return nil, nil, fmt.Errorf("invalid --add value %q", arguments[index])
		}
		archivePath := strings.ReplaceAll(parts[0], "\\", "/")
		if strings.HasPrefix(archivePath, "/") || strings.Contains(archivePath, "../") || archivePath == ".." {
			return nil, nil, fmt.Errorf("unsafe archive path %q", archivePath)
		}
		if _, exists := additions[archivePath]; exists {
			return nil, nil, fmt.Errorf("duplicate addition %q", archivePath)
		}
		additions[archivePath] = addition{
			sourcePath:    parts[1],
			normalizeText: flag == "--add-text",
		}
	}
	return additions, paths, nil
}

func normalizeTextLineEndings(data []byte) []byte {
	data = bytes.ReplaceAll(data, []byte("\r\n"), []byte("\n"))
	return bytes.ReplaceAll(data, []byte("\r"), []byte("\n"))
}
