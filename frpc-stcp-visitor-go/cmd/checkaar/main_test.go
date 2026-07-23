package main

import (
	"bytes"
	"encoding/base64"
	debug "runtime/debug"
	"strings"
	"testing"

	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

func TestValidateAndDigestModuleGraphAcceptsExpectedVersionedGraph(t *testing.T) {
	versions, expectations, info := validBuildInfo(t)
	first, err := validateAndDigestModuleGraph(info, versions, expectations)
	if err != nil {
		t.Fatal(err)
	}
	for left, right := 0, len(info.Deps)-1; left < right; left, right = left+1, right-1 {
		info.Deps[left], info.Deps[right] = info.Deps[right], info.Deps[left]
	}
	second, err := validateAndDigestModuleGraph(info, versions, expectations)
	if err != nil {
		t.Fatal(err)
	}
	if first != second || !isLowerSHA256(first) {
		t.Fatalf("digests differ or are invalid: %q %q", first, second)
	}
}

func TestValidateAndDigestModuleGraphRejectsLocalPathWithoutLeakingIt(t *testing.T) {
	versions, expectations, info := validBuildInfo(t)
	const secretPath = `E:\private\customer\token-source`
	for _, dependency := range info.Deps {
		if dependency.Path == reprobuild.ANetModulePath {
			dependency.Replace.Path = secretPath
			dependency.Replace.Version = "(devel)"
			dependency.Replace.Sum = ""
			break
		}
	}
	_, err := validateAndDigestModuleGraph(info, versions, expectations)
	if err == nil {
		t.Fatal("expected local replacement to fail")
	}
	if strings.Contains(err.Error(), "private") || strings.Contains(err.Error(), "customer") ||
		strings.Contains(err.Error(), "token-source") {
		t.Fatalf("error leaked local path: %q", err)
	}
}

func TestValidateAndDigestModuleGraphRejectsRelativeReplacement(t *testing.T) {
	versions, expectations, info := validBuildInfo(t)
	for _, dependency := range info.Deps {
		if dependency.Path == reprobuild.ANetModulePath {
			dependency.Replace.Path = "../anetcompat"
			break
		}
	}
	if _, err := validateAndDigestModuleGraph(info, versions, expectations); err == nil {
		t.Fatal("expected relative replacement to fail")
	}
}

func TestValidateAndDigestModuleGraphRejectsDevelDependency(t *testing.T) {
	versions, expectations, info := validBuildInfo(t)
	info.Deps = append(info.Deps, &debug.Module{Path: "example.com/local", Version: "(devel)"})
	if _, err := validateAndDigestModuleGraph(info, versions, expectations); err == nil {
		t.Fatal("expected devel dependency to fail")
	}
}

func TestValidateAndDigestModuleGraphRejectsMissingExpectedModule(t *testing.T) {
	versions, expectations, info := validBuildInfo(t)
	filtered := info.Deps[:0]
	for _, dependency := range info.Deps {
		if dependency.Path != reprobuild.FRPModulePath {
			filtered = append(filtered, dependency)
		}
	}
	info.Deps = filtered
	if _, err := validateAndDigestModuleGraph(info, versions, expectations); err == nil {
		t.Fatal("expected missing patched frp module to fail")
	}
}

func TestAcceptGraphDigestRejectsCrossABIDifference(t *testing.T) {
	current := strings.Repeat("a", 64)
	if err := acceptGraphDigest(&current, strings.Repeat("b", 64)); err == nil {
		t.Fatal("expected differing ABI graph digests to fail")
	}
}

func TestBuildLocalPathMarkersRejectsSlashAndBackslashForms(t *testing.T) {
	markers, err := buildLocalPathMarkers(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	matchedSlash := false
	matchedBackslash := false
	for _, marker := range markers {
		if bytes.Contains(marker, []byte("/")) {
			matchedSlash = containsLocalBuildPath(append([]byte("prefix:"), marker...), markers)
		}
		if bytes.Contains(marker, []byte("\\")) {
			matchedBackslash = containsLocalBuildPath(append([]byte("prefix:"), marker...), markers)
		}
	}
	if !matchedSlash || !matchedBackslash {
		t.Fatalf("local path markers did not cover both separator forms: slash=%v backslash=%v", matchedSlash, matchedBackslash)
	}
}

func TestContainsLocalBuildPathDoesNotMatchUnrelatedData(t *testing.T) {
	markers, err := buildLocalPathMarkers(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if containsLocalBuildPath([]byte("versioned module graph without checkout paths"), markers) {
		t.Fatal("unrelated native data matched a local path marker")
	}
}

func validBuildInfo(t *testing.T) (reprobuild.Versions, []reprobuild.ModuleExpectation, *debug.BuildInfo) {
	t.Helper()
	versions := reprobuild.Versions{
		BridgeVersion:  "0.3.11-frp0.69.1-p1",
		GoVersion:      "go1.26.4",
		XMobileVersion: "v0.0.0-20260611195102-4dd8f1dbf5d2",
		AndroidAPI:     26,
		NDKVersion:     "27.1.12297006",
	}
	expectations, err := versions.ExpectedModules()
	if err != nil {
		t.Fatal(err)
	}
	deps := make([]*debug.Module, 0, len(expectations)+1)
	for index, expectation := range expectations {
		dependency := &debug.Module{
			Path:    expectation.Module.Path,
			Version: expectation.Module.Version,
			Sum:     testModuleSum(byte(index + 1)),
		}
		if expectation.Replacement != nil {
			dependency.Sum = ""
			dependency.Replace = &debug.Module{
				Path:    expectation.Replacement.Path,
				Version: expectation.Replacement.Version,
				Sum:     testModuleSum(byte(index + 20)),
			}
		}
		deps = append(deps, dependency)
	}
	deps = append(deps, &debug.Module{
		Path:    "example.com/dependency",
		Version: "v1.2.3",
		Sum:     testModuleSum(99),
	})
	return versions, expectations, &debug.BuildInfo{
		GoVersion: versions.GoVersion,
		Path:      "gobind/gobind",
		Main:      debug.Module{Path: "gobind", Version: "(devel)"},
		Deps:      deps,
	}
}

func testModuleSum(value byte) string {
	return "h1:" + base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{value}, 32))
}
