package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"debug/elf"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"regexp"
	"sort"
	"strings"
)

const (
	profileGoMobileEmergencyRelease = "gomobile-emergency-release"
	profileKotlinRelease            = "kotlin-release"
	requiredPageAlignment           = 16 * 1024
	maxNativeLibraryBytes           = 256 * 1024 * 1024
)

var (
	machines = map[string]elf.Machine{
		"arm64-v8a":   elf.EM_AARCH64,
		"armeabi-v7a": elf.EM_ARM,
		"x86":         elf.EM_386,
		"x86_64":      elf.EM_X86_64,
	}
	nativeEntryPattern = regexp.MustCompile(`^lib/([^/]+)/([^/]+\.so)$`)
)

type libraryReport struct {
	Path             string `json:"path"`
	ABI              string `json:"abi"`
	Machine          string `json:"machine"`
	MinLoadAlignment uint64 `json:"minLoadAlignment"`
	Stripped         bool   `json:"stripped"`
	SHA256           string `json:"sha256"`
}

type report struct {
	Profile   string          `json:"profile"`
	ABI       string          `json:"abi"`
	Libraries []libraryReport `json:"libraries"`
}

func main() {
	result, err := run(os.Args[1:])
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

func run(args []string) (report, error) {
	if len(args) == 0 {
		return report{}, fmt.Errorf("usage: checkapk <%s|%s> <apk> <abi> [expected-libgojni-sha256]", profileGoMobileEmergencyRelease, profileKotlinRelease)
	}
	profile := args[0]
	switch profile {
	case profileGoMobileEmergencyRelease:
		if len(args) != 4 {
			return report{}, fmt.Errorf("usage: checkapk %s <apk> <abi> <expected-libgojni-sha256>", profile)
		}
		return inspectAPK(profile, args[1], args[2], args[3])
	case profileKotlinRelease:
		if len(args) != 3 {
			return report{}, fmt.Errorf("usage: checkapk %s <apk> <abi>", profile)
		}
		return inspectAPK(profile, args[1], args[2], "")
	default:
		return report{}, fmt.Errorf("unsupported artifact profile %q", profile)
	}
}

func inspectAPK(profile string, path string, abi string, expectedGoJNIHash string) (report, error) {
	expectedMachine, ok := machines[abi]
	if !ok {
		return report{}, fmt.Errorf("unsupported ABI %q", abi)
	}
	if profile == profileGoMobileEmergencyRelease {
		if !isLowercaseSHA256(expectedGoJNIHash) {
			return report{}, fmt.Errorf("expected libgojni SHA-256 must be 64 lowercase hexadecimal characters")
		}
	} else if profile != profileKotlinRelease {
		return report{}, fmt.Errorf("unsupported artifact profile %q", profile)
	} else if expectedGoJNIHash != "" {
		return report{}, fmt.Errorf("Kotlin release must not accept an expected libgojni SHA-256")
	}

	archive, err := zip.OpenReader(path)
	if err != nil {
		return report{}, fmt.Errorf("open APK: %w", err)
	}
	defer archive.Close()

	seen := make(map[string]struct{})
	libraries := make([]libraryReport, 0)
	goJNIPath := "lib/" + abi + "/libgojni.so"
	goJNICount := 0
	for _, entry := range archive.File {
		if !strings.HasPrefix(entry.Name, "lib/") || !strings.HasSuffix(entry.Name, ".so") {
			continue
		}
		matches := nativeEntryPattern.FindStringSubmatch(entry.Name)
		if matches == nil {
			return report{}, fmt.Errorf("APK contains malformed native library entry %s", entry.Name)
		}
		if _, exists := seen[entry.Name]; exists {
			return report{}, fmt.Errorf("APK contains duplicate native library entry %s", entry.Name)
		}
		seen[entry.Name] = struct{}{}
		entryABI := matches[1]
		if entryABI != abi {
			return report{}, fmt.Errorf("APK contains unexpected native ABI entry %s", entry.Name)
		}
		if matches[2] == "libgojni.so" {
			if profile == profileKotlinRelease {
				return report{}, fmt.Errorf("Kotlin release APK contains forbidden %s", entry.Name)
			}
			goJNICount++
		}
		library, err := inspectNativeLibrary(entry, abi, expectedMachine)
		if err != nil {
			return report{}, fmt.Errorf("verify %s: %w", entry.Name, err)
		}
		libraries = append(libraries, library)
	}

	if profile == profileGoMobileEmergencyRelease {
		if goJNICount != 1 {
			return report{}, fmt.Errorf("APK must contain exactly one %s", goJNIPath)
		}
		for _, library := range libraries {
			if library.Path == goJNIPath && library.SHA256 != expectedGoJNIHash {
				return report{}, fmt.Errorf("%s SHA-256 is %s, want %s", goJNIPath, library.SHA256, expectedGoJNIHash)
			}
		}
	}

	sort.Slice(libraries, func(i, j int) bool { return libraries[i].Path < libraries[j].Path })
	return report{Profile: profile, ABI: abi, Libraries: libraries}, nil
}

func inspectNativeLibrary(entry *zip.File, abi string, expectedMachine elf.Machine) (libraryReport, error) {
	if entry.UncompressedSize64 == 0 || entry.UncompressedSize64 > maxNativeLibraryBytes {
		return libraryReport{}, fmt.Errorf("uncompressed size must be between 1 and %d bytes", maxNativeLibraryBytes)
	}
	reader, err := entry.Open()
	if err != nil {
		return libraryReport{}, err
	}
	data, readErr := io.ReadAll(io.LimitReader(reader, maxNativeLibraryBytes+1))
	closeErr := reader.Close()
	if readErr != nil {
		return libraryReport{}, readErr
	}
	if closeErr != nil {
		return libraryReport{}, closeErr
	}
	if len(data) == 0 || len(data) > maxNativeLibraryBytes || uint64(len(data)) != entry.UncompressedSize64 {
		return libraryReport{}, fmt.Errorf("native library size is invalid or changed while reading")
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
			return libraryReport{}, fmt.Errorf("PT_LOAD alignment is %#x, require at least %#x", program.Align, requiredPageAlignment)
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
		Path:             entry.Name,
		ABI:              abi,
		Machine:          binary.Machine.String(),
		MinLoadAlignment: minAlignment,
		Stripped:         true,
		SHA256:           fmt.Sprintf("%x", sha256.Sum256(data)),
	}, nil
}

func isLowercaseSHA256(value string) bool {
	if len(value) != sha256.Size*2 || value != strings.ToLower(value) {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}
