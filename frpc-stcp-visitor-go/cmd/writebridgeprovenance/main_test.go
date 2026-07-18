package main

import (
	"strings"
	"testing"

	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

func TestBuildProvenanceCarriesSafeModuleGraphProof(t *testing.T) {
	versions := reprobuild.Versions{
		BridgeVersion:  "0.3.7-frp0.69.1-p1",
		GoVersion:      "go1.26.4",
		XMobileVersion: "v0.0.0-20260611195102-4dd8f1dbf5d2",
		AndroidAPI:     26,
		NDKVersion:     "27.1.12297006",
	}
	digest := strings.Repeat("a", 64)
	result, err := buildProvenance(versions, nativeValidation{
		SchemaVersion:                   2,
		ModuleGraphSHA256:               digest,
		ModuleGraphLocalPathFree:        true,
		ModuleGraphConsistentAcrossABIs: true,
	}, strings.Repeat("b", 64))
	if err != nil {
		t.Fatal(err)
	}
	if result.SchemaVersion != 2 || result.ModuleGraphSHA256 != digest || !result.ModuleGraphLocalPathFree {
		t.Fatalf("unexpected provenance: %+v", result)
	}
}
