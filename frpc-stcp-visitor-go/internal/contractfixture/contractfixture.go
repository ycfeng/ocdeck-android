package contractfixture

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"sort"
	"strings"

	frpmsg "github.com/fatedier/frp/pkg/msg"
	libcrypto "github.com/fatedier/golib/crypto"
	fmux "github.com/hashicorp/yamux"
	"golang.org/x/mod/modfile"
)

const (
	schemaVersion          = 1
	generatorVersion       = "k0-go-oracle-v4"
	manifestFileName       = "manifest.json"
	maxCanonicalFileSize   = 1 << 20
	maxCanonicalFileCount  = 128
	canonicalRelativePath  = "frpc-stcp-visitor/src/test/resources/io/github/ycfeng/ocdeck/frpcstcpvisitor/contract/v1"
	bridgePropertiesPath   = "frpc-stcp-visitor-go/bridge-versions.properties"
	goModulePath           = "frpc-stcp-visitor-go/go.mod"
	preparedGoModulePath   = "frpc-stcp-visitor-go/build/frp-patched.mod"
	downstreamRelativePath = "frpc-stcp-visitor-go/downstream"
)

type Result struct {
	Directory string
	Entries   int
}

type manifest struct {
	SchemaVersion    int         `json:"schemaVersion"`
	GeneratorVersion string      `json:"generatorVersion"`
	Pins             pins        `json:"pins"`
	Provenance       provenance  `json:"provenance"`
	Limits           limits      `json:"limits"`
	Entries          []entry     `json:"entries"`
	ChunkPlans       []chunkPlan `json:"chunkPlans"`
	Mutations        []mutation  `json:"mutations"`
}

type pins struct {
	Bridge string `json:"bridge"`
	FRP    string `json:"frp"`
	GoLib  string `json:"golib"`
	Yamux  string `json:"yamux"`
}

type provenance struct {
	Synthetic               bool   `json:"synthetic"`
	Generator               string `json:"generator"`
	Source                  string `json:"source"`
	License                 string `json:"license"`
	ContainsCapturedTraffic bool   `json:"containsCapturedTraffic"`
	ContainsRawSecrets      bool   `json:"containsRawSecrets"`
}

type limits struct {
	WireV1JSONLength     int64  `json:"wireV1JsonLength"`
	WireV2FramePayload   uint32 `json:"wireV2FramePayload"`
	YamuxHeader          int    `json:"yamuxHeader"`
	YamuxInitialWindow   uint32 `json:"yamuxInitialWindow"`
	YamuxFRPStreamWindow uint32 `json:"yamuxFrpStreamWindow"`
}

type entry struct {
	ID       string `json:"id"`
	Path     string `json:"path"`
	Layer    string `json:"layer"`
	Length   int    `json:"length"`
	SHA256   string `json:"sha256"`
	Expected string `json:"expected"`
}

type chunkPlan struct {
	ID       string `json:"id"`
	Source   string `json:"source"`
	Sizes    []int  `json:"sizes"`
	Expected string `json:"expected"`
}

type mutation struct {
	ID        string `json:"id"`
	Source    string `json:"source"`
	Operation string `json:"operation"`
	Offset    *int   `json:"offset,omitempty"`
	ByteValue *uint8 `json:"byteValue,omitempty"`
	IntValue  *int64 `json:"intValue,omitempty"`
	Count     *int   `json:"count,omitempty"`
	Mask      *uint8 `json:"mask,omitempty"`
	Expected  string `json:"expected"`
}

type generatedSet struct {
	manifest manifest
	files    map[string][]byte
	spec     *oracleSpec
}

type downstreamManifest struct {
	Module  string `json:"module"`
	Version string `json:"version"`
}

func Check() (Result, error) {
	root, err := repositoryRoot()
	if err != nil {
		return Result{}, err
	}
	expected, err := generateFixtureSet(root)
	if err != nil {
		return Result{}, err
	}
	directory := filepath.Join(root, filepath.FromSlash(canonicalRelativePath))
	actual, err := readFixtureDirectory(directory)
	if err != nil {
		return Result{}, err
	}
	if err := compareFileSets(expected.files, actual); err != nil {
		return Result{}, fmt.Errorf("canonical contract fixtures are stale: %w", err)
	}
	if err := validateFixtureSet(actual, expected.manifest, expected.spec); err != nil {
		return Result{}, fmt.Errorf("canonical contract fixtures failed self-check: %w", err)
	}
	return Result{Directory: canonicalRelativePath, Entries: len(expected.manifest.Entries)}, nil
}

func Write() (Result, error) {
	root, err := repositoryRoot()
	if err != nil {
		return Result{}, err
	}
	first, err := generateFixtureSet(root)
	if err != nil {
		return Result{}, err
	}
	second, err := generateFixtureSet(root)
	if err != nil {
		return Result{}, err
	}
	if err := compareFileSets(first.files, second.files); err != nil {
		return Result{}, fmt.Errorf("contract fixture generation is not deterministic: %w", err)
	}
	if err := validateFixtureSet(first.files, first.manifest, first.spec); err != nil {
		return Result{}, fmt.Errorf("generated contract fixtures failed self-check: %w", err)
	}

	directory := filepath.Join(root, filepath.FromSlash(canonicalRelativePath))
	parent := filepath.Dir(directory)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return Result{}, fmt.Errorf("create canonical fixture parent: %w", err)
	}
	temporary, err := os.MkdirTemp(parent, ".v1-write-")
	if err != nil {
		return Result{}, fmt.Errorf("create temporary fixture directory: %w", err)
	}
	defer os.RemoveAll(temporary)
	if err := os.Chmod(temporary, 0o755); err != nil {
		return Result{}, fmt.Errorf("set temporary fixture directory permissions: %w", err)
	}

	if err := writeFixtureDirectory(temporary, first.files); err != nil {
		return Result{}, err
	}
	written, err := readFixtureDirectory(temporary)
	if err != nil {
		return Result{}, err
	}
	if err := compareFileSets(first.files, written); err != nil {
		return Result{}, fmt.Errorf("temporary contract fixture set differs after write: %w", err)
	}
	if err := validateFixtureSet(written, first.manifest, first.spec); err != nil {
		return Result{}, fmt.Errorf("temporary contract fixtures failed self-check: %w", err)
	}
	if err := replaceCanonicalDirectory(temporary, directory); err != nil {
		return Result{}, err
	}

	return Result{Directory: canonicalRelativePath, Entries: len(first.manifest.Entries)}, nil
}

func repositoryRoot() (string, error) {
	workingDirectory, err := os.Getwd()
	if err != nil {
		return "", fmt.Errorf("resolve working directory: %w", err)
	}
	current, err := filepath.Abs(workingDirectory)
	if err != nil {
		return "", fmt.Errorf("resolve absolute working directory: %w", err)
	}
	for {
		module := filepath.Join(current, filepath.FromSlash(goModulePath))
		bridge := filepath.Join(current, "frpc-stcp-visitor")
		if isRegularFile(module) && isDirectory(bridge) {
			return current, nil
		}
		if filepath.Base(current) == "frpc-stcp-visitor-go" && isRegularFile(filepath.Join(current, "go.mod")) {
			parent := filepath.Dir(current)
			if isDirectory(filepath.Join(parent, "frpc-stcp-visitor")) {
				return parent, nil
			}
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return "", fmt.Errorf("repository root was not found from %s", filepath.Clean(workingDirectory))
}

func isRegularFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

func isDirectory(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func readPins(root string) (pins, error) {
	propertiesData, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(bridgePropertiesPath)))
	if err != nil {
		return pins{}, fmt.Errorf("read bridge version pins: %w", err)
	}
	properties := make(map[string]string)
	for _, line := range strings.Split(string(propertiesData), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return pins{}, fmt.Errorf("invalid bridge version pin line")
		}
		properties[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	bridgeVersion := properties["BRIDGE_VERSION"]
	if bridgeVersion == "" {
		return pins{}, fmt.Errorf("BRIDGE_VERSION pin is missing")
	}

	goModData, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(goModulePath)))
	if err != nil {
		return pins{}, fmt.Errorf("read Go module pins: %w", err)
	}
	parsed, err := modfile.Parse("go.mod", goModData, nil)
	if err != nil {
		return pins{}, fmt.Errorf("parse Go module pins")
	}
	frpVersion := requiredVersion(parsed, "github.com/fatedier/frp")
	goLibVersion := requiredVersion(parsed, "github.com/fatedier/golib")
	if frpVersion == "" || goLibVersion == "" {
		return pins{}, fmt.Errorf("required frp or golib pin is missing")
	}

	var yamuxModule, yamuxVersion string
	for _, replacement := range parsed.Replace {
		if replacement.Old.Path == "github.com/hashicorp/yamux" {
			yamuxModule = replacement.New.Path
			yamuxVersion = replacement.New.Version
			break
		}
	}
	if yamuxModule == "" || yamuxVersion == "" {
		return pins{}, fmt.Errorf("pinned yamux replacement is missing")
	}

	downstreamRoot := filepath.Join(root, filepath.FromSlash(downstreamRelativePath))
	downstreamEntries, err := os.ReadDir(downstreamRoot)
	if err != nil {
		return pins{}, fmt.Errorf("read downstream frp pins: %w", err)
	}
	var downstreamName string
	var downstream downstreamManifest
	for _, candidate := range downstreamEntries {
		if !candidate.IsDir() {
			continue
		}
		manifestPath := filepath.Join(downstreamRoot, candidate.Name(), "manifest.json")
		data, readErr := os.ReadFile(manifestPath)
		if readErr != nil {
			continue
		}
		var parsedManifest downstreamManifest
		if json.Unmarshal(data, &parsedManifest) != nil {
			continue
		}
		if parsedManifest.Module == "github.com/fatedier/frp" && parsedManifest.Version == frpVersion {
			if downstreamName != "" {
				return pins{}, fmt.Errorf("multiple downstream frp manifests match the active pin")
			}
			downstreamName = candidate.Name()
			downstream = parsedManifest
		}
	}
	if downstreamName == "" || downstream.Module == "" {
		return pins{}, fmt.Errorf("active downstream frp manifest was not found")
	}
	if err := validatePreparedModuleGraph(
		root,
		parsed,
		downstreamName,
		frpVersion,
		goLibVersion,
		yamuxModule,
		yamuxVersion,
	); err != nil {
		return pins{}, err
	}
	if err := validateActiveImplementations(root, downstreamName, goLibVersion, yamuxModule, yamuxVersion); err != nil {
		return pins{}, err
	}

	return pins{
		Bridge: bridgeVersion,
		FRP:    fmt.Sprintf("github.com/fatedier/frp@%s (%s)", frpVersion, downstreamName),
		GoLib:  "github.com/fatedier/golib@" + goLibVersion,
		Yamux:  yamuxModule + "@" + yamuxVersion,
	}, nil
}

func validatePreparedModuleGraph(
	root string,
	base *modfile.File,
	downstreamName string,
	frpVersion string,
	goLibVersion string,
	yamuxModule string,
	yamuxVersion string,
) error {
	preparedPath := filepath.Join(root, filepath.FromSlash(preparedGoModulePath))
	data, err := os.ReadFile(preparedPath)
	if err != nil {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}
	prepared, err := modfile.Parse("frp-patched.mod", data, nil)
	if err != nil {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}
	if prepared.Module == nil || base.Module == nil || prepared.Module.Mod.Path != base.Module.Mod.Path ||
		requiredVersion(prepared, "github.com/fatedier/frp") != frpVersion ||
		requiredVersion(prepared, "github.com/fatedier/golib") != goLibVersion ||
		requiredVersion(prepared, "github.com/hashicorp/yamux") != requiredVersion(base, "github.com/hashicorp/yamux") {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}

	frpReplacement, ok := replacementFor(prepared, "github.com/fatedier/frp")
	if !ok || frpReplacement.New.Version != "" {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}
	expectedFRP := filepath.Join(root, "frpc-stcp-visitor-go", "build", downstreamName)
	actualFRP := frpReplacement.New.Path
	if !filepath.IsAbs(actualFRP) {
		actualFRP = filepath.Join(filepath.Dir(preparedPath), actualFRP)
	}
	if !samePath(actualFRP, expectedFRP) {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}

	yamuxReplacement, ok := replacementFor(prepared, "github.com/hashicorp/yamux")
	if !ok || yamuxReplacement.New.Path != yamuxModule || yamuxReplacement.New.Version != yamuxVersion {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}
	return nil
}

func replacementFor(file *modfile.File, module string) (*modfile.Replace, bool) {
	var found *modfile.Replace
	for _, replacement := range file.Replace {
		if replacement.Old.Path != module || replacement.Old.Version != "" {
			continue
		}
		if found != nil {
			return nil, false
		}
		found = replacement
	}
	return found, found != nil
}

func validateActiveImplementations(root, downstreamName, goLibVersion, yamuxModule, yamuxVersion string) error {
	expected := filepath.Join(root, "frpc-stcp-visitor-go", "build", downstreamName)
	if !linkedFunctionIsWithin(frpmsg.WriteMsg, expected) {
		return fmt.Errorf("contract fixture oracle requires the prepared build/frp-patched.mod module graph")
	}
	if !linkedFunctionHasModuleVersion(libcrypto.NewReader, "github.com/fatedier/golib", goLibVersion) {
		return fmt.Errorf("contract fixture oracle requires the pinned golib module graph")
	}
	if !linkedFunctionHasModuleVersion(fmux.DefaultConfig, yamuxModule, yamuxVersion) {
		return fmt.Errorf("contract fixture oracle requires the pinned yamux module graph")
	}
	return nil
}

func linkedFunctionIsWithin(functionValue any, expected string) bool {
	function := runtime.FuncForPC(reflect.ValueOf(functionValue).Pointer())
	if function == nil {
		return false
	}
	sourceFile, _ := function.FileLine(function.Entry())
	return isPathWithin(sourceFile, expected)
}

func linkedFunctionHasModuleVersion(functionValue any, module, version string) bool {
	function := runtime.FuncForPC(reflect.ValueOf(functionValue).Pointer())
	if function == nil {
		return false
	}
	sourceFile, _ := function.FileLine(function.Entry())
	needle := "/" + strings.ToLower(module) + "@" + strings.ToLower(version) + "/"
	normalized := "/" + strings.ToLower(filepath.ToSlash(sourceFile))
	return strings.Contains(normalized, needle)
}

func samePath(first, second string) bool {
	firstAbsolute, firstErr := filepath.Abs(first)
	secondAbsolute, secondErr := filepath.Abs(second)
	if firstErr != nil || secondErr != nil {
		return false
	}
	firstClean := filepath.Clean(firstAbsolute)
	secondClean := filepath.Clean(secondAbsolute)
	if runtime.GOOS == "windows" {
		return strings.EqualFold(firstClean, secondClean)
	}
	return firstClean == secondClean
}

func isPathWithin(path, directory string) bool {
	pathAbsolute, pathErr := filepath.Abs(path)
	directoryAbsolute, directoryErr := filepath.Abs(directory)
	if pathErr != nil || directoryErr != nil {
		return false
	}
	relative, err := filepath.Rel(filepath.Clean(directoryAbsolute), filepath.Clean(pathAbsolute))
	if err != nil || relative == "." || relative == ".." || filepath.IsAbs(relative) {
		return false
	}
	outside := strings.HasPrefix(relative, ".."+string(filepath.Separator))
	if runtime.GOOS == "windows" {
		return !strings.HasPrefix(strings.ToLower(relative), ".."+string(filepath.Separator))
	}
	return !outside
}

func requiredVersion(file *modfile.File, module string) string {
	for _, requirement := range file.Require {
		if requirement.Mod.Path == module {
			return requirement.Mod.Version
		}
	}
	return ""
}

func readFixtureDirectory(root string) (map[string][]byte, error) {
	if !isDirectory(root) {
		return nil, fmt.Errorf("canonical fixture directory is missing: %s", root)
	}
	files := make(map[string][]byte)
	err := filepath.WalkDir(root, func(path string, directoryEntry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == root {
			return nil
		}
		if directoryEntry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("fixture set contains a symbolic link")
		}
		if directoryEntry.IsDir() {
			return nil
		}
		if !directoryEntry.Type().IsRegular() {
			return fmt.Errorf("fixture set contains a non-regular file")
		}
		if len(files) >= maxCanonicalFileCount {
			return fmt.Errorf("fixture file count exceeds %d", maxCanonicalFileCount)
		}
		info, err := directoryEntry.Info()
		if err != nil {
			return err
		}
		if info.Size() < 0 || info.Size() > maxCanonicalFileSize {
			return fmt.Errorf("fixture file exceeds the %d-byte limit", maxCanonicalFileSize)
		}
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		openedInfo, statErr := file.Stat()
		if statErr != nil {
			_ = file.Close()
			return statErr
		}
		if !openedInfo.Mode().IsRegular() || !os.SameFile(info, openedInfo) {
			_ = file.Close()
			return fmt.Errorf("fixture file changed while it was being read")
		}
		data, readErr := io.ReadAll(io.LimitReader(file, maxCanonicalFileSize+1))
		closeErr := file.Close()
		if readErr != nil {
			return readErr
		}
		if closeErr != nil {
			return closeErr
		}
		if len(data) > maxCanonicalFileSize {
			return fmt.Errorf("fixture file exceeds the %d-byte limit", maxCanonicalFileSize)
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		files[filepath.ToSlash(relative)] = data
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("read fixture set: %w", err)
	}
	return files, nil
}

func writeFixtureDirectory(root string, files map[string][]byte) error {
	paths := sortedFilePaths(files)
	for _, relative := range paths {
		if err := validateRelativePath(relative); err != nil {
			return err
		}
		target := filepath.Join(root, filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return fmt.Errorf("create fixture directory for %s: %w", relative, err)
		}
		if err := os.WriteFile(target, files[relative], 0o644); err != nil {
			return fmt.Errorf("write fixture %s: %w", relative, err)
		}
	}
	return nil
}

func replaceCanonicalDirectory(temporary, canonical string) error {
	parent := filepath.Dir(canonical)
	backup, err := os.MkdirTemp(parent, ".v1-backup-")
	if err != nil {
		return fmt.Errorf("reserve canonical fixture backup path: %w", err)
	}
	if err := os.Remove(backup); err != nil {
		return fmt.Errorf("prepare canonical fixture backup path: %w", err)
	}

	hadCanonical := false
	if _, err := os.Stat(canonical); err == nil {
		hadCanonical = true
		if err := os.Rename(canonical, backup); err != nil {
			return fmt.Errorf("move previous canonical fixture set aside: %w", err)
		}
	} else if !os.IsNotExist(err) {
		return fmt.Errorf("inspect canonical fixture set: %w", err)
	}

	if err := os.Rename(temporary, canonical); err != nil {
		if hadCanonical {
			if rollbackErr := os.Rename(backup, canonical); rollbackErr != nil {
				return fmt.Errorf(
					"install canonical fixture set: %w; restore previous canonical fixture set: %v",
					err,
					rollbackErr,
				)
			}
		}
		return fmt.Errorf("install canonical fixture set: %w", err)
	}
	if hadCanonical {
		// Installation is already committed. A stale backup is safer than
		// reporting an ambiguous failed write after the canonical set changed.
		_ = os.RemoveAll(backup)
	}
	return nil
}

func compareFileSets(expected, actual map[string][]byte) error {
	for _, path := range sortedFilePaths(expected) {
		actualData, ok := actual[path]
		if !ok {
			return fmt.Errorf("missing file %s", path)
		}
		expectedData := expected[path]
		expectedHash := sha256Hex(expectedData)
		actualHash := sha256Hex(actualData)
		if len(expectedData) != len(actualData) {
			return fmt.Errorf("length mismatch for %s: got %d with SHA-256 %s, want %d with SHA-256 %s", path, len(actualData), actualHash, len(expectedData), expectedHash)
		}
		if actualHash != expectedHash || !bytes.Equal(actualData, expectedData) {
			return fmt.Errorf("content mismatch for %s: got SHA-256 %s, want %s", path, actualHash, expectedHash)
		}
	}
	for _, path := range sortedFilePaths(actual) {
		if _, ok := expected[path]; !ok {
			return fmt.Errorf("unexpected file %s", path)
		}
	}
	return nil
}

func validateFixtureSet(files map[string][]byte, expected manifest, spec *oracleSpec) error {
	manifestData, ok := files[manifestFileName]
	if !ok {
		return fmt.Errorf("manifest is missing")
	}
	if bytes.Contains(manifestData, []byte(syntheticToken)) || bytes.Contains(manifestData, []byte(syntheticSTCPSecret)) {
		return fmt.Errorf("manifest contains a raw synthetic credential")
	}

	decoder := json.NewDecoder(bytes.NewReader(manifestData))
	decoder.DisallowUnknownFields()
	var actual manifest
	if err := decoder.Decode(&actual); err != nil {
		return fmt.Errorf("manifest is invalid")
	}
	if err := requireJSONEOF(decoder); err != nil {
		return err
	}
	if !reflect.DeepEqual(actual, expected) {
		return fmt.Errorf("manifest fields differ from the deterministic oracle")
	}
	if actual.SchemaVersion != schemaVersion || actual.GeneratorVersion != generatorVersion {
		return fmt.Errorf("manifest schema or generator version is unsupported")
	}
	if !actual.Provenance.Synthetic || actual.Provenance.ContainsCapturedTraffic || actual.Provenance.ContainsRawSecrets || actual.Provenance.License == "" {
		return fmt.Errorf("manifest provenance declaration is invalid")
	}

	entryPaths := make(map[string]struct{}, len(actual.Entries))
	entryIDs := make(map[string]struct{}, len(actual.Entries))
	for _, item := range actual.Entries {
		if item.ID == "" || item.Layer == "" || item.Expected == "" {
			return fmt.Errorf("manifest entry metadata is incomplete")
		}
		if _, duplicate := entryIDs[item.ID]; duplicate {
			return fmt.Errorf("manifest contains duplicate entry IDs")
		}
		entryIDs[item.ID] = struct{}{}
		if err := validateRelativePath(item.Path); err != nil {
			return err
		}
		if item.Path == manifestFileName {
			return fmt.Errorf("manifest must not hash itself")
		}
		if _, duplicate := entryPaths[item.Path]; duplicate {
			return fmt.Errorf("manifest contains duplicate entry paths")
		}
		entryPaths[item.Path] = struct{}{}
		data, exists := files[item.Path]
		if !exists {
			return fmt.Errorf("manifest references missing file %s", item.Path)
		}
		if item.Length != len(data) || item.SHA256 != sha256Hex(data) {
			return fmt.Errorf("manifest length or SHA-256 is stale for %s", item.Path)
		}
		if bytes.Contains(data, []byte(syntheticToken)) || bytes.Contains(data, []byte(syntheticSTCPSecret)) {
			return fmt.Errorf("fixture %s contains a raw synthetic credential", item.Path)
		}
	}
	for path := range files {
		if path == manifestFileName {
			continue
		}
		if _, ok := entryPaths[path]; !ok {
			return fmt.Errorf("file %s is not declared by the manifest", path)
		}
	}

	chunkIDs := make(map[string]struct{}, len(actual.ChunkPlans))
	for _, plan := range actual.ChunkPlans {
		if plan.ID == "" || plan.Source == "" || plan.Expected == "" || len(plan.Sizes) == 0 {
			return fmt.Errorf("chunk plan metadata is incomplete")
		}
		if _, duplicate := chunkIDs[plan.ID]; duplicate {
			return fmt.Errorf("manifest contains duplicate chunk plan IDs")
		}
		chunkIDs[plan.ID] = struct{}{}
		if _, exists := entryPaths[plan.Source]; !exists {
			return fmt.Errorf("chunk plan references undeclared source %s", plan.Source)
		}
		for _, size := range plan.Sizes {
			if size <= 0 {
				return fmt.Errorf("chunk plan contains a non-positive size")
			}
		}
	}

	mutationIDs := make(map[string]struct{}, len(actual.Mutations))
	for _, recipe := range actual.Mutations {
		if recipe.ID == "" || recipe.Source == "" || recipe.Operation == "" || recipe.Expected == "" {
			return fmt.Errorf("mutation metadata is incomplete")
		}
		if _, duplicate := mutationIDs[recipe.ID]; duplicate {
			return fmt.Errorf("manifest contains duplicate mutation IDs")
		}
		mutationIDs[recipe.ID] = struct{}{}
		if _, exists := entryPaths[recipe.Source]; !exists {
			return fmt.Errorf("mutation references undeclared source %s", recipe.Source)
		}
	}

	return validateOracleSemantics(files, spec, actual)
}

func requireJSONEOF(decoder *json.Decoder) error {
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return fmt.Errorf("manifest contains trailing JSON data")
	}
	return nil
}

func validateRelativePath(path string) error {
	if path == "" || path == "." || strings.Contains(path, "\\") || filepath.IsAbs(path) {
		return fmt.Errorf("invalid fixture relative path")
	}
	clean := filepath.ToSlash(filepath.Clean(filepath.FromSlash(path)))
	if clean != path || clean == ".." || strings.HasPrefix(clean, "../") {
		return fmt.Errorf("invalid fixture relative path")
	}
	return nil
}

func sortedFilePaths(files map[string][]byte) []string {
	paths := make([]string, 0, len(files))
	for path := range files {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	return paths
}

func sha256Hex(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}
