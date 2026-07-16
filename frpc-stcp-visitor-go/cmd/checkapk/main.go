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
	"strings"
)

const requiredPageAlignment = 16 * 1024

var machines = map[string]elf.Machine{
	"arm64-v8a":   elf.EM_AARCH64,
	"armeabi-v7a": elf.EM_ARM,
	"x86":         elf.EM_386,
	"x86_64":      elf.EM_X86_64,
}

type report struct {
	ABI              string `json:"abi"`
	Machine          string `json:"machine"`
	MinLoadAlignment uint64 `json:"minLoadAlignment"`
	Stripped         bool   `json:"stripped"`
	SHA256           string `json:"sha256"`
}

func main() {
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: checkapk <apk> <abi> <expected-libgojni-sha256>")
		os.Exit(2)
	}
	result, err := inspectAPK(os.Args[1], os.Args[2], os.Args[3])
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

func inspectAPK(path string, abi string, expectedSHA256 string) (report, error) {
	expectedMachine, ok := machines[abi]
	if !ok {
		return report{}, fmt.Errorf("unsupported ABI %q", abi)
	}
	if len(expectedSHA256) != sha256.Size*2 {
		return report{}, fmt.Errorf("expected SHA-256 must contain %d hexadecimal characters", sha256.Size*2)
	}
	if _, err := hex.DecodeString(expectedSHA256); err != nil {
		return report{}, fmt.Errorf("invalid expected SHA-256: %w", err)
	}
	archive, err := zip.OpenReader(path)
	if err != nil {
		return report{}, fmt.Errorf("open APK: %w", err)
	}
	defer archive.Close()

	expectedPath := "lib/" + abi + "/libgojni.so"
	var expectedEntry *zip.File
	for _, entry := range archive.File {
		if entry.Name == expectedPath {
			if expectedEntry != nil {
				return report{}, fmt.Errorf("APK contains duplicate entry %s", expectedPath)
			}
			expectedEntry = entry
			continue
		}
		if strings.HasPrefix(entry.Name, "lib/") && strings.HasSuffix(entry.Name, "/libgojni.so") {
			return report{}, fmt.Errorf("APK contains unexpected libgojni ABI entry %s", entry.Name)
		}
	}
	if expectedEntry == nil {
		return report{}, fmt.Errorf("APK is missing %s", expectedPath)
	}
	reader, err := expectedEntry.Open()
	if err != nil {
		return report{}, err
	}
	data, readErr := io.ReadAll(reader)
	closeErr := reader.Close()
	if readErr != nil {
		return report{}, readErr
	}
	if closeErr != nil {
		return report{}, closeErr
	}
	actualSHA256 := fmt.Sprintf("%x", sha256.Sum256(data))
	if actualSHA256 != strings.ToLower(expectedSHA256) {
		return report{}, fmt.Errorf("%s SHA-256 is %s, want %s", expectedPath, actualSHA256, expectedSHA256)
	}

	binary, err := elf.NewFile(bytes.NewReader(data))
	if err != nil {
		return report{}, err
	}
	defer binary.Close()
	if binary.Machine != expectedMachine {
		return report{}, fmt.Errorf("machine is %s, want %s", binary.Machine, expectedMachine)
	}
	minAlignment := ^uint64(0)
	loadSegments := 0
	for _, program := range binary.Progs {
		if program.Type != elf.PT_LOAD {
			continue
		}
		loadSegments++
		if program.Align < requiredPageAlignment {
			return report{}, fmt.Errorf(
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
		return report{}, fmt.Errorf("ELF contains no PT_LOAD segments")
	}
	for _, section := range binary.Sections {
		if strings.HasPrefix(section.Name, ".debug_") || section.Name == ".symtab" || section.Name == ".strtab" {
			return report{}, fmt.Errorf("ELF still contains debug/symbol section %s", section.Name)
		}
	}
	return report{
		ABI:              abi,
		Machine:          binary.Machine.String(),
		MinLoadAlignment: minAlignment,
		Stripped:         true,
		SHA256:           actualSHA256,
	}, nil
}
