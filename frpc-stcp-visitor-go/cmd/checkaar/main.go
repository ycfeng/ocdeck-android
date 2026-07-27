package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"debug/buildinfo"
	"debug/elf"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	debug "runtime/debug"
	"sort"
	"strings"

	"golang.org/x/mod/module"

	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

const requiredPageAlignment = 16 * 1024

var requiredLibraries = map[string]elf.Machine{
	"jni/arm64-v8a/libgojni.so":   elf.EM_AARCH64,
	"jni/armeabi-v7a/libgojni.so": elf.EM_ARM,
	"jni/x86/libgojni.so":         elf.EM_386,
	"jni/x86_64/libgojni.so":      elf.EM_X86_64,
}

type report struct {
	SchemaVersion                   int             `json:"schemaVersion"`
	PageAlignment                   int             `json:"pageAlignment"`
	Stripped                        bool            `json:"stripped"`
	ModuleGraphSHA256               string          `json:"moduleGraphSha256"`
	ModuleGraphLocalPathFree        bool            `json:"moduleGraphLocalPathFree"`
	ModuleGraphConsistentAcrossABIs bool            `json:"moduleGraphConsistentAcrossAbis"`
	Libraries                       []libraryReport `json:"libraries"`
}

type libraryReport struct {
	ABI              string `json:"abi"`
	Machine          string `json:"machine"`
	MinLoadAlignment uint64 `json:"minLoadAlignment"`
	SHA256           string `json:"sha256"`
}

type expectedMetadata struct {
	entries           map[string][]byte
	moduleGraphSHA256 string
}

type canonicalGraph struct {
	GoVersion    string            `json:"goVersion"`
	Dependencies []canonicalModule `json:"dependencies"`
}

type canonicalModule struct {
	Path        string             `json:"path"`
	Version     string             `json:"version"`
	Sum         string             `json:"sum,omitempty"`
	Replacement *canonicalIdentity `json:"replacement,omitempty"`
}

type canonicalIdentity struct {
	Path    string `json:"path"`
	Version string `json:"version"`
	Sum     string `json:"sum"`
}

func main() {
	repositoryRoot := flag.String("root", "", "repository root containing legal files")
	expectedAPI := flag.String("api", "", "expected javap signature file")
	expectedBridgeProvenance := flag.String("bridge-provenance", "", "embedded bridge provenance file")
	expectedFRPProvenance := flag.String("frp-provenance", "", "frp patch provenance file")
	versionsPath := flag.String("versions", "", "bridge versions properties")
	nativeOnly := flag.Bool("native-only", false, "validate only native libraries and Go module graphs")
	flag.Parse()
	if flag.NArg() != 1 || *versionsPath == "" || *repositoryRoot == "" || (!*nativeOnly &&
		(*expectedAPI == "" || *expectedBridgeProvenance == "" || *expectedFRPProvenance == "")) {
		fmt.Fprintln(
			os.Stderr,
			"usage: checkaar -versions <properties> [-native-only | "+
				"-root <repository-root> -api <api-file> -bridge-provenance <json> "+
				"-frp-provenance <json>] <aar>",
		)
		os.Exit(2)
	}

	versions, err := reprobuild.ReadVersions(*versionsPath)
	if err != nil {
		fail(err)
	}
	expectedModules, err := versions.ExpectedModules()
	if err != nil {
		fail(err)
	}
	metadata := expectedMetadata{}
	if !*nativeOnly {
		metadata, err = loadExpectedMetadata(
			*repositoryRoot,
			*expectedAPI,
			*expectedBridgeProvenance,
			*expectedFRPProvenance,
		)
		if err != nil {
			fail(err)
		}
	}
	localPathMarkers, err := buildLocalPathMarkers(*repositoryRoot)
	if err != nil {
		fail(err)
	}
	result, err := inspectAAR(flag.Arg(0), metadata.entries, versions, expectedModules, localPathMarkers)
	if err != nil {
		fail(err)
	}
	if metadata.moduleGraphSHA256 != "" && result.ModuleGraphSHA256 != metadata.moduleGraphSHA256 {
		fail(errors.New("native module graph does not match bridge provenance"))
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		fail(errors.New("encode AAR validation report"))
	}
}

func inspectAAR(
	path string,
	metadata map[string][]byte,
	versions reprobuild.Versions,
	expectedModules []reprobuild.ModuleExpectation,
	localPathMarkers [][]byte,
) (report, error) {
	archive, err := zip.OpenReader(path)
	if err != nil {
		return report{}, errors.New("open AAR")
	}
	defer archive.Close()

	entries := make(map[string]*zip.File, len(archive.File))
	for _, entry := range archive.File {
		if _, exists := entries[entry.Name]; exists {
			return report{}, fmt.Errorf("AAR contains duplicate entry %s", entry.Name)
		}
		entries[entry.Name] = entry
	}
	if metadata != nil {
		if err := verifyMetadata(entries, metadata); err != nil {
			return report{}, err
		}
	}

	paths := make([]string, 0, len(requiredLibraries))
	for path := range requiredLibraries {
		paths = append(paths, path)
	}
	sort.Strings(paths)

	result := report{
		SchemaVersion:                   2,
		PageAlignment:                   requiredPageAlignment,
		Stripped:                        true,
		ModuleGraphLocalPathFree:        true,
		ModuleGraphConsistentAcrossABIs: true,
		Libraries:                       make([]libraryReport, 0, len(paths)),
	}
	for _, libraryPath := range paths {
		entry := entries[libraryPath]
		if entry == nil {
			return report{}, fmt.Errorf("AAR is missing required native library %s", libraryPath)
		}
		library, graphDigest, err := inspectLibrary(
			entry,
			requiredLibraries[libraryPath],
			versions,
			expectedModules,
			localPathMarkers,
		)
		if err != nil {
			return report{}, fmt.Errorf("inspect %s: %w", libraryPath, err)
		}
		if err := acceptGraphDigest(&result.ModuleGraphSHA256, graphDigest); err != nil {
			return report{}, err
		}
		library.ABI = strings.Split(libraryPath, "/")[1]
		result.Libraries = append(result.Libraries, library)
	}

	for name := range entries {
		if strings.HasPrefix(name, "jni/") && strings.HasSuffix(name, "/libgojni.so") {
			if _, expected := requiredLibraries[name]; !expected {
				return report{}, fmt.Errorf("AAR contains unexpected native ABI entry %s", name)
			}
		}
	}
	return result, nil
}

func loadExpectedMetadata(
	repositoryRoot string,
	expectedAPI string,
	expectedBridgeProvenance string,
	expectedFRPProvenance string,
) (expectedMetadata, error) {
	paths := map[string]string{
		"META-INF/OCDECK/LICENSE.txt":               filepath.Join(repositoryRoot, "LICENSE"),
		"META-INF/OCDECK/NOTICE.txt":                filepath.Join(repositoryRoot, "NOTICE"),
		"META-INF/OCDECK/THIRD_PARTY_NOTICES.txt":   filepath.Join(repositoryRoot, "THIRD_PARTY_NOTICES.txt"),
		"META-INF/OCDECK/TRADEMARKS.md":             filepath.Join(repositoryRoot, "TRADEMARKS.md"),
		"META-INF/OCDECK/bridge-api.txt":            expectedAPI,
		"META-INF/OCDECK/bridge-provenance.json":    expectedBridgeProvenance,
		"META-INF/OCDECK/frp-patch-provenance.json": expectedFRPProvenance,
	}
	licenseDirectory := filepath.Join(repositoryRoot, "third_party", "licenses")
	licenses, err := os.ReadDir(licenseDirectory)
	if err != nil {
		return expectedMetadata{}, errors.New("read third-party licenses")
	}
	for _, license := range licenses {
		if license.IsDir() {
			continue
		}
		paths["META-INF/OCDECK/licenses/"+license.Name()] = filepath.Join(licenseDirectory, license.Name())
	}
	expected := make(map[string][]byte, len(paths))
	for archivePath, sourcePath := range paths {
		data, err := os.ReadFile(sourcePath)
		if err != nil {
			return expectedMetadata{}, fmt.Errorf("read expected metadata %s", archivePath)
		}
		expected[archivePath] = normalizeTextLineEndings(data)
	}
	var provenance struct {
		ModuleGraphSHA256        string `json:"moduleGraphSha256"`
		ModuleGraphLocalPathFree bool   `json:"moduleGraphLocalPathFree"`
	}
	if err := json.Unmarshal(expected["META-INF/OCDECK/bridge-provenance.json"], &provenance); err != nil {
		return expectedMetadata{}, errors.New("decode bridge provenance")
	}
	if !isLowerSHA256(provenance.ModuleGraphSHA256) || !provenance.ModuleGraphLocalPathFree {
		return expectedMetadata{}, errors.New("bridge provenance module graph proof is invalid")
	}
	return expectedMetadata{entries: expected, moduleGraphSHA256: provenance.ModuleGraphSHA256}, nil
}

func normalizeTextLineEndings(data []byte) []byte {
	data = bytes.ReplaceAll(data, []byte("\r\n"), []byte("\n"))
	return bytes.ReplaceAll(data, []byte("\r"), []byte("\n"))
}

func verifyMetadata(entries map[string]*zip.File, expected map[string][]byte) error {
	for path, expectedData := range expected {
		entry := entries[path]
		if entry == nil {
			return fmt.Errorf("AAR is missing required metadata %s", path)
		}
		reader, err := entry.Open()
		if err != nil {
			return fmt.Errorf("open metadata %s", path)
		}
		data, readErr := io.ReadAll(reader)
		closeErr := reader.Close()
		if readErr != nil {
			return fmt.Errorf("read metadata %s", path)
		}
		if closeErr != nil {
			return fmt.Errorf("close metadata %s", path)
		}
		if !bytes.Equal(data, expectedData) {
			return fmt.Errorf("AAR metadata does not match source file: %s", path)
		}
	}
	for path := range entries {
		if strings.HasPrefix(path, "META-INF/OCDECK/licenses/") {
			if _, ok := expected[path]; !ok {
				return fmt.Errorf("AAR contains unexpected third-party license %s", path)
			}
		}
	}
	return nil
}

func inspectLibrary(
	entry *zip.File,
	expectedMachine elf.Machine,
	versions reprobuild.Versions,
	expectedModules []reprobuild.ModuleExpectation,
	localPathMarkers [][]byte,
) (libraryReport, string, error) {
	reader, err := entry.Open()
	if err != nil {
		return libraryReport{}, "", errors.New("open native library")
	}
	data, readErr := io.ReadAll(reader)
	closeErr := reader.Close()
	if readErr != nil {
		return libraryReport{}, "", errors.New("read native library")
	}
	if closeErr != nil {
		return libraryReport{}, "", errors.New("close native library")
	}
	if containsLocalBuildPath(data, localPathMarkers) {
		return libraryReport{}, "", errors.New("native library contains a local build path")
	}

	binary, err := elf.NewFile(bytes.NewReader(data))
	if err != nil {
		return libraryReport{}, "", errors.New("decode native ELF")
	}
	defer binary.Close()
	if binary.Machine != expectedMachine {
		return libraryReport{}, "", fmt.Errorf("machine is %s, want %s", binary.Machine, expectedMachine)
	}

	minAlignment := ^uint64(0)
	loadSegments := 0
	for _, program := range binary.Progs {
		if program.Type != elf.PT_LOAD {
			continue
		}
		loadSegments++
		if program.Align < requiredPageAlignment {
			return libraryReport{}, "", fmt.Errorf(
				"PT_LOAD alignment is %#x, require at least %#x",
				program.Align,
				requiredPageAlignment,
			)
		}
		if program.Align < minAlignment {
			minAlignment = program.Align
		}
	}
	if loadSegments == 0 {
		return libraryReport{}, "", errors.New("ELF contains no PT_LOAD segments")
	}

	for _, section := range binary.Sections {
		if strings.HasPrefix(section.Name, ".debug_") || section.Name == ".symtab" || section.Name == ".strtab" {
			return libraryReport{}, "", fmt.Errorf("ELF still contains debug/symbol section %s", section.Name)
		}
	}
	info, err := buildinfo.Read(bytes.NewReader(data))
	if err != nil {
		return libraryReport{}, "", errors.New("read native Go build information")
	}
	graphDigest, err := validateAndDigestModuleGraph(info, versions, expectedModules)
	if err != nil {
		return libraryReport{}, "", err
	}
	return libraryReport{
		Machine:          binary.Machine.String(),
		MinLoadAlignment: minAlignment,
		SHA256:           fmt.Sprintf("%x", sha256.Sum256(data)),
	}, graphDigest, nil
}

func validateAndDigestModuleGraph(
	info *debug.BuildInfo,
	versions reprobuild.Versions,
	expectedModules []reprobuild.ModuleExpectation,
) (string, error) {
	if info == nil || info.GoVersion != versions.GoVersion || info.Path != "gobind/gobind" ||
		info.Main.Path != "gobind" || info.Main.Version != "(devel)" {
		return "", errors.New("native Go build identity is invalid")
	}
	dependencies := make([]canonicalModule, 0, len(info.Deps))
	byPath := make(map[string]*debug.Module, len(info.Deps))
	for _, dependency := range info.Deps {
		if dependency == nil {
			return "", errors.New("native module graph contains an invalid dependency")
		}
		if _, exists := byPath[dependency.Path]; exists {
			return "", errors.New("native module graph contains a duplicate dependency")
		}
		if err := validateModuleIdentity(dependency.Path, dependency.Version); err != nil {
			return "", err
		}
		if dependency.Replace == nil {
			if !reprobuild.IsValidModuleSum(dependency.Sum) {
				return "", errors.New("native module graph contains an unhashed dependency")
			}
		} else {
			if err := validateModuleIdentity(dependency.Replace.Path, dependency.Replace.Version); err != nil {
				return "", err
			}
			if !reprobuild.IsValidModuleSum(dependency.Replace.Sum) {
				return "", errors.New("native module graph contains an unhashed replacement")
			}
		}
		byPath[dependency.Path] = dependency
		canonical := canonicalModule{
			Path:    dependency.Path,
			Version: dependency.Version,
			Sum:     dependency.Sum,
		}
		if dependency.Replace != nil {
			canonical.Replacement = &canonicalIdentity{
				Path:    dependency.Replace.Path,
				Version: dependency.Replace.Version,
				Sum:     dependency.Replace.Sum,
			}
		}
		dependencies = append(dependencies, canonical)
	}

	expectedByPath := make(map[string]reprobuild.ModuleExpectation, len(expectedModules))
	for _, expected := range expectedModules {
		expectedByPath[expected.Module.Path] = expected
		actual := byPath[expected.Module.Path]
		if actual == nil || actual.Version != expected.Module.Version {
			return "", fmt.Errorf("native module graph %s dependency is missing or changed", expected.Label)
		}
		if expected.Replacement == nil {
			if actual.Replace != nil {
				return "", fmt.Errorf("native module graph %s dependency has an unexpected replacement", expected.Label)
			}
			continue
		}
		if actual.Replace == nil || actual.Replace.Path != expected.Replacement.Path ||
			actual.Replace.Version != expected.Replacement.Version {
			return "", fmt.Errorf("native module graph %s replacement is missing or changed", expected.Label)
		}
	}
	for _, dependency := range info.Deps {
		if dependency.Replace == nil {
			continue
		}
		expected, ok := expectedByPath[dependency.Path]
		if !ok || expected.Replacement == nil {
			return "", errors.New("native module graph contains an unexpected replacement")
		}
	}

	sort.Slice(dependencies, func(i, j int) bool { return dependencies[i].Path < dependencies[j].Path })
	encoded, err := json.Marshal(canonicalGraph{GoVersion: info.GoVersion, Dependencies: dependencies})
	if err != nil {
		return "", errors.New("encode native module graph")
	}
	digest := fmt.Sprintf("%x", sha256.Sum256(encoded))
	return digest, nil
}

func validateModuleIdentity(path string, version string) error {
	if isLocalModulePath(path) {
		return errors.New("native module graph contains a local-path dependency or replacement")
	}
	if !reprobuild.IsStableModuleVersion(version) {
		return errors.New("native module graph contains a dependency or replacement without a stable version")
	}
	if err := module.Check(path, version); err != nil {
		return errors.New("native module graph contains an invalid module identity")
	}
	return nil
}

func isLocalModulePath(path string) bool {
	normalized := strings.ReplaceAll(path, "\\", "/")
	lower := strings.ToLower(normalized)
	if strings.HasPrefix(lower, "file:") || strings.HasPrefix(normalized, "/") ||
		strings.HasPrefix(normalized, "./") || strings.HasPrefix(normalized, "../") {
		return true
	}
	return len(normalized) >= 3 && normalized[1] == ':' && normalized[2] == '/' &&
		((normalized[0] >= 'a' && normalized[0] <= 'z') || (normalized[0] >= 'A' && normalized[0] <= 'Z'))
}

func buildLocalPathMarkers(repositoryRoot string) ([][]byte, error) {
	absoluteRoot, err := filepath.Abs(repositoryRoot)
	if err != nil {
		return nil, errors.New("resolve repository root")
	}
	candidates := map[string]struct{}{}
	addPathMarkers(candidates, absoluteRoot)
	if resolvedRoot, resolveErr := filepath.EvalSymlinks(absoluteRoot); resolveErr == nil {
		addPathMarkers(candidates, resolvedRoot)
	}
	for _, environmentKey := range []string{"GOCACHE", "GOMODCACHE"} {
		if value := os.Getenv(environmentKey); value != "" && value != "off" {
			addPathMarkers(candidates, value)
		}
	}
	for _, goPath := range filepath.SplitList(os.Getenv("GOPATH")) {
		if goPath != "" {
			addPathMarkers(candidates, goPath)
		}
	}
	markers := make([][]byte, 0, len(candidates))
	for candidate := range candidates {
		if len(candidate) >= 8 {
			markers = append(markers, []byte(candidate))
		}
	}
	sort.Slice(markers, func(i, j int) bool { return bytes.Compare(markers[i], markers[j]) < 0 })
	if len(markers) == 0 {
		return nil, errors.New("repository root is too short for native path validation")
	}
	return markers, nil
}

func addPathMarkers(markers map[string]struct{}, path string) {
	clean := filepath.Clean(path)
	markers[clean] = struct{}{}
	withSlashes := filepath.ToSlash(clean)
	markers[withSlashes] = struct{}{}
	markers[strings.ReplaceAll(withSlashes, "/", "\\")] = struct{}{}
}

func containsLocalBuildPath(data []byte, markers [][]byte) bool {
	for _, marker := range markers {
		if bytes.Contains(data, marker) {
			return true
		}
	}
	return false
}

func acceptGraphDigest(current *string, next string) error {
	if *current == "" {
		*current = next
		return nil
	}
	if *current != next {
		return errors.New("native Go module graph differs across ABIs")
	}
	return nil
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
