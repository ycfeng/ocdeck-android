package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
)

type provenance struct {
	SchemaVersion       int      `json:"schemaVersion"`
	BridgeVersion       string   `json:"bridgeVersion"`
	BridgeAPIVersion    int      `json:"bridgeApiVersion"`
	GoVersion           string   `json:"goVersion"`
	XMobileVersion      string   `json:"xMobileVersion"`
	AndroidAPI          int      `json:"androidApi"`
	NDKVersion          string   `json:"ndkVersion"`
	FRPVersion          string   `json:"frpVersion"`
	FRPPatch            string   `json:"frpPatch"`
	NativePageAlignment int      `json:"nativePageAlignment"`
	NativeStripped      bool     `json:"nativeStripped"`
	NativeABIs          []string `json:"nativeAbis"`
	AARSHA256           string   `json:"aarSha256,omitempty"`
}

func main() {
	versionsPath := flag.String("versions", "bridge-versions.properties", "bridge versions properties")
	outputPath := flag.String("output", "", "output JSON path")
	aarSHA256 := flag.String("aar-sha256", "", "optional final AAR SHA-256")
	flag.Parse()
	if *outputPath == "" {
		fmt.Fprintln(os.Stderr, "-output is required")
		os.Exit(2)
	}
	values, err := readProperties(*versionsPath)
	if err != nil {
		fail(err)
	}
	androidAPI, err := strconv.Atoi(values["ANDROID_API"])
	if err != nil {
		fail(fmt.Errorf("invalid ANDROID_API: %w", err))
	}
	result := provenance{
		SchemaVersion:       1,
		BridgeVersion:       values["BRIDGE_VERSION"],
		BridgeAPIVersion:    2,
		GoVersion:           values["GO_VERSION"],
		XMobileVersion:      values["XMOBILE_VERSION"],
		AndroidAPI:          androidAPI,
		NDKVersion:          values["NDK_VERSION"],
		FRPVersion:          "v0.69.1",
		FRPPatch:            "frp-v0.69.1-p1",
		NativePageAlignment: 16384,
		NativeStripped:      true,
		NativeABIs:          []string{"arm64-v8a", "armeabi-v7a", "x86", "x86_64"},
		AARSHA256:           *aarSHA256,
	}
	data, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fail(err)
	}
	data = append(data, '\n')
	if err := os.WriteFile(*outputPath, data, 0o644); err != nil {
		fail(err)
	}
}

func readProperties(path string) (map[string]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	values := make(map[string]string)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 || strings.TrimSpace(parts[0]) == "" {
			return nil, fmt.Errorf("invalid properties line %q", line)
		}
		values[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	for _, key := range []string{"BRIDGE_VERSION", "GO_VERSION", "XMOBILE_VERSION", "ANDROID_API", "NDK_VERSION"} {
		if values[key] == "" {
			return nil, errors.New("missing property " + key)
		}
	}
	return values, nil
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
