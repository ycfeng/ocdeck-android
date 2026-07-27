package reprobuild

import "testing"

func TestBridgeGoModuleVersion(t *testing.T) {
	versions := Versions{
		BridgeVersion:  "0.3.11-frp0.69.1-p1",
		GoVersion:      "go1.26.4",
		XMobileVersion: "v0.0.0-20260611195102-4dd8f1dbf5d2",
		AndroidAPI:     26,
		NDKVersion:     "27.1.12297006",
	}
	got, err := versions.BridgeGoModuleVersion()
	if err != nil {
		t.Fatal(err)
	}
	if got != "v0.3.11" {
		t.Fatalf("version = %q, want v0.3.11", got)
	}
}

func TestBridgeGoModuleVersionRejectsMismatchedPatch(t *testing.T) {
	versions := Versions{BridgeVersion: "0.3.11-frp0.69.1"}
	if _, err := versions.BridgeGoModuleVersion(); err == nil {
		t.Fatal("expected mismatched frp suffix to fail")
	}
}

func TestPinnedModuleVersionsAreStable(t *testing.T) {
	for _, version := range []string{
		ANetCompatModuleVersion,
		ANetModuleVersion,
		FRPModuleVersion,
		YamuxModuleVersion,
		YamuxReplacementVersion,
	} {
		if !IsStableModuleVersion(version) {
			t.Fatalf("version %q is not stable", version)
		}
	}
}
