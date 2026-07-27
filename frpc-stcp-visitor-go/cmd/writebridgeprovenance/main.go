package main

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"

	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

type provenance struct {
	SchemaVersion            int      `json:"schemaVersion"`
	BridgeVersion            string   `json:"bridgeVersion"`
	BridgeAPIVersion         int      `json:"bridgeApiVersion"`
	GoVersion                string   `json:"goVersion"`
	XMobileVersion           string   `json:"xMobileVersion"`
	AndroidAPI               int      `json:"androidApi"`
	NDKVersion               string   `json:"ndkVersion"`
	FRPVersion               string   `json:"frpVersion"`
	FRPPatch                 string   `json:"frpPatch"`
	NativePageAlignment      int      `json:"nativePageAlignment"`
	NativeStripped           bool     `json:"nativeStripped"`
	NativeABIs               []string `json:"nativeAbis"`
	ModuleGraphSHA256        string   `json:"moduleGraphSha256"`
	ModuleGraphLocalPathFree bool     `json:"moduleGraphLocalPathFree"`
	AARSHA256                string   `json:"aarSha256,omitempty"`
}

type nativeValidation struct {
	SchemaVersion                   int    `json:"schemaVersion"`
	ModuleGraphSHA256               string `json:"moduleGraphSha256"`
	ModuleGraphLocalPathFree        bool   `json:"moduleGraphLocalPathFree"`
	ModuleGraphConsistentAcrossABIs bool   `json:"moduleGraphConsistentAcrossAbis"`
}

func main() {
	versionsPath := flag.String("versions", "bridge-versions.properties", "bridge versions properties")
	outputPath := flag.String("output", "", "output JSON path")
	nativeReportPath := flag.String("native-report", "", "validated native metadata report")
	aarSHA256 := flag.String("aar-sha256", "", "optional final AAR SHA-256")
	flag.Parse()
	if *outputPath == "" || *nativeReportPath == "" {
		fmt.Fprintln(os.Stderr, "-output and -native-report are required")
		os.Exit(2)
	}
	versions, err := reprobuild.ReadVersions(*versionsPath)
	if err != nil {
		fail(err)
	}
	native, err := readNativeValidation(*nativeReportPath)
	if err != nil {
		fail(err)
	}
	result, err := buildProvenance(versions, native, *aarSHA256)
	if err != nil {
		fail(err)
	}
	data, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fail(errors.New("encode bridge provenance"))
	}
	data = append(data, '\n')
	if err := os.WriteFile(*outputPath, data, 0o644); err != nil {
		fail(errors.New("write bridge provenance"))
	}
}

func readNativeValidation(path string) (nativeValidation, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nativeValidation{}, errors.New("read native validation report")
	}
	var result nativeValidation
	if err := json.Unmarshal(data, &result); err != nil {
		return nativeValidation{}, errors.New("decode native validation report")
	}
	if result.SchemaVersion != 2 || !isLowerSHA256(result.ModuleGraphSHA256) ||
		!result.ModuleGraphLocalPathFree || !result.ModuleGraphConsistentAcrossABIs {
		return nativeValidation{}, errors.New("native validation module graph proof is invalid")
	}
	return result, nil
}

func buildProvenance(
	versions reprobuild.Versions,
	native nativeValidation,
	aarSHA256 string,
) (provenance, error) {
	if err := versions.Validate(); err != nil {
		return provenance{}, err
	}
	if native.SchemaVersion != 2 || !isLowerSHA256(native.ModuleGraphSHA256) ||
		!native.ModuleGraphLocalPathFree || !native.ModuleGraphConsistentAcrossABIs {
		return provenance{}, errors.New("native validation module graph proof is invalid")
	}
	if aarSHA256 != "" && !isLowerSHA256(aarSHA256) {
		return provenance{}, errors.New("AAR SHA-256 is invalid")
	}
	return provenance{
		SchemaVersion:            2,
		BridgeVersion:            versions.BridgeVersion,
		BridgeAPIVersion:         2,
		GoVersion:                versions.GoVersion,
		XMobileVersion:           versions.XMobileVersion,
		AndroidAPI:               versions.AndroidAPI,
		NDKVersion:               versions.NDKVersion,
		FRPVersion:               reprobuild.FRPUpstreamVersion,
		FRPPatch:                 reprobuild.FRPPatchName,
		NativePageAlignment:      16384,
		NativeStripped:           true,
		NativeABIs:               []string{"arm64-v8a", "armeabi-v7a", "x86", "x86_64"},
		ModuleGraphSHA256:        native.ModuleGraphSHA256,
		ModuleGraphLocalPathFree: true,
		AARSHA256:                aarSHA256,
	}, nil
}

func isLowerSHA256(value string) bool {
	if len(value) != sha256.Size*2 {
		return false
	}
	for _, char := range value {
		if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
			return false
		}
	}
	return true
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
