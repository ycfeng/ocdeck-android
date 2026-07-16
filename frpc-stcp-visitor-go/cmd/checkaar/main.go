package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"debug/elf"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const requiredPageAlignment = 16 * 1024

var requiredLibraries = map[string]elf.Machine{
	"jni/arm64-v8a/libgojni.so":   elf.EM_AARCH64,
	"jni/armeabi-v7a/libgojni.so": elf.EM_ARM,
	"jni/x86/libgojni.so":         elf.EM_386,
	"jni/x86_64/libgojni.so":      elf.EM_X86_64,
}

type report struct {
	PageAlignment int             `json:"pageAlignment"`
	Stripped      bool            `json:"stripped"`
	Libraries     []libraryReport `json:"libraries"`
}

type libraryReport struct {
	ABI              string `json:"abi"`
	Machine          string `json:"machine"`
	MinLoadAlignment uint64 `json:"minLoadAlignment"`
	SHA256           string `json:"sha256"`
}

func main() {
	repositoryRoot := flag.String("root", "", "repository root containing legal files")
	expectedAPI := flag.String("api", "", "expected javap signature file")
	expectedBridgeProvenance := flag.String("bridge-provenance", "", "embedded bridge provenance file")
	expectedFRPProvenance := flag.String("frp-provenance", "", "frp patch provenance file")
	flag.Parse()
	if flag.NArg() != 1 || *repositoryRoot == "" || *expectedAPI == "" ||
		*expectedBridgeProvenance == "" || *expectedFRPProvenance == "" {
		fmt.Fprintln(
			os.Stderr,
			"usage: checkaar -root <repository-root> -api <api-file> "+
				"-bridge-provenance <json> -frp-provenance <json> <aar>",
		)
		os.Exit(2)
	}

	expectedMetadata, err := loadExpectedMetadata(
		*repositoryRoot,
		*expectedAPI,
		*expectedBridgeProvenance,
		*expectedFRPProvenance,
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	result, err := inspectAAR(flag.Arg(0), expectedMetadata)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func inspectAAR(path string, expectedMetadata map[string][]byte) (report, error) {
	archive, err := zip.OpenReader(path)
	if err != nil {
		return report{}, fmt.Errorf("open AAR: %w", err)
	}
	defer archive.Close()

	entries := make(map[string]*zip.File, len(archive.File))
	for _, entry := range archive.File {
		if _, exists := entries[entry.Name]; exists {
			return report{}, fmt.Errorf("AAR contains duplicate entry %s", entry.Name)
		}
		entries[entry.Name] = entry
	}
	if err := verifyMetadata(entries, expectedMetadata); err != nil {
		return report{}, err
	}

	paths := make([]string, 0, len(requiredLibraries))
	for path := range requiredLibraries {
		paths = append(paths, path)
	}
	sort.Strings(paths)

	result := report{
		PageAlignment: requiredPageAlignment,
		Stripped:      true,
		Libraries:     make([]libraryReport, 0, len(paths)),
	}
	for _, libraryPath := range paths {
		entry := entries[libraryPath]
		if entry == nil {
			return report{}, fmt.Errorf("AAR is missing required native library %s", libraryPath)
		}
		library, err := inspectLibrary(entry, requiredLibraries[libraryPath])
		if err != nil {
			return report{}, fmt.Errorf("inspect %s: %w", libraryPath, err)
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
) (map[string][]byte, error) {
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
		return nil, fmt.Errorf("read third-party licenses: %w", err)
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
			return nil, fmt.Errorf("read expected metadata %s: %w", sourcePath, err)
		}
		expected[archivePath] = data
	}
	return expected, nil
}

func verifyMetadata(entries map[string]*zip.File, expected map[string][]byte) error {
	for path, expectedData := range expected {
		entry := entries[path]
		if entry == nil {
			return fmt.Errorf("AAR is missing required metadata %s", path)
		}
		reader, err := entry.Open()
		if err != nil {
			return fmt.Errorf("open metadata %s: %w", path, err)
		}
		data, readErr := io.ReadAll(reader)
		closeErr := reader.Close()
		if readErr != nil {
			return fmt.Errorf("read metadata %s: %w", path, readErr)
		}
		if closeErr != nil {
			return fmt.Errorf("close metadata %s: %w", path, closeErr)
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

func inspectLibrary(entry *zip.File, expectedMachine elf.Machine) (libraryReport, error) {
	reader, err := entry.Open()
	if err != nil {
		return libraryReport{}, err
	}
	data, err := io.ReadAll(reader)
	closeErr := reader.Close()
	if err != nil {
		return libraryReport{}, err
	}
	if closeErr != nil {
		return libraryReport{}, closeErr
	}

	binary, err := elf.NewFile(bytes.NewReader(data))
	if err != nil {
		return libraryReport{}, err
	}
	defer binary.Close()
	if binary.Machine != expectedMachine {
		return libraryReport{}, fmt.Errorf("machine is %s, want %s", binary.Machine, expectedMachine)
	}

	minAlignment := ^uint64(0)
	loadSegments := 0
	for _, program := range binary.Progs {
		if program.Type != elf.PT_LOAD {
			continue
		}
		loadSegments++
		if program.Align < requiredPageAlignment {
			return libraryReport{}, fmt.Errorf(
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
		return libraryReport{}, fmt.Errorf("ELF contains no PT_LOAD segments")
	}

	for _, section := range binary.Sections {
		if strings.HasPrefix(section.Name, ".debug_") || section.Name == ".symtab" || section.Name == ".strtab" {
			return libraryReport{}, fmt.Errorf("ELF still contains debug/symbol section %s", section.Name)
		}
	}
	return libraryReport{
		Machine:          binary.Machine.String(),
		MinLoadAlignment: minAlignment,
		SHA256:           fmt.Sprintf("%x", sha256.Sum256(data)),
	}, nil
}
