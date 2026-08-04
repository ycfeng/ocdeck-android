package main

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunRejectsUnknownProfileAndWrongArity(t *testing.T) {
	for _, args := range [][]string{
		{},
		{"unknown", "app.apk", "arm64-v8a"},
		{profileGoMobileEmergencyRelease, "app.apk", "arm64-v8a"},
		{profileKotlinRelease, "app.apk", "arm64-v8a", strings.Repeat("0", 64)},
	} {
		if _, err := run(args); err == nil {
			t.Fatalf("run(%q) unexpectedly succeeded", args)
		}
	}
}

func TestKotlinProfileAllowsNoNativeLibraries(t *testing.T) {
	apk := writeTestAPK(t, nil)
	result, err := inspectAPK(profileKotlinRelease, apk, "arm64-v8a", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Libraries) != 0 {
		t.Fatalf("libraries = %v, want none", result.Libraries)
	}
}

func TestKotlinProfileValidatesOtherNativeLibraries(t *testing.T) {
	apk := writeTestAPK(t, []testZipEntry{{"lib/arm64-v8a/libother.so", minimalELF64(183, requiredPageAlignment)}})
	result, err := inspectAPK(profileKotlinRelease, apk, "arm64-v8a", "")
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Libraries) != 1 || result.Libraries[0].Path != "lib/arm64-v8a/libother.so" {
		t.Fatalf("unexpected report: %+v", result)
	}
}

func TestKotlinProfileRejectsGoJNI(t *testing.T) {
	apk := writeTestAPK(t, []testZipEntry{{"lib/arm64-v8a/libgojni.so", minimalELF64(183, requiredPageAlignment)}})
	if _, err := inspectAPK(profileKotlinRelease, apk, "arm64-v8a", ""); err == nil {
		t.Fatal("Kotlin profile accepted libgojni.so")
	}
}

func TestGoMobileProfileRequiresMatchingGoJNI(t *testing.T) {
	data := minimalELF64(183, requiredPageAlignment)
	apk := writeTestAPK(t, []testZipEntry{{"lib/arm64-v8a/libgojni.so", data}})
	hash := fmt.Sprintf("%x", sha256.Sum256(data))
	if _, err := inspectAPK(profileGoMobileEmergencyRelease, apk, "arm64-v8a", hash); err != nil {
		t.Fatal(err)
	}
	if _, err := inspectAPK(profileGoMobileEmergencyRelease, apk, "arm64-v8a", strings.Repeat("0", 64)); err == nil {
		t.Fatal("GoMobile profile accepted the wrong libgojni hash")
	}
}

func TestProfilesRejectInvalidNativeLayout(t *testing.T) {
	tests := []struct {
		name    string
		entries []testZipEntry
	}{
		{"other ABI", []testZipEntry{{"lib/x86_64/libother.so", minimalELF64(62, requiredPageAlignment)}}},
		{"duplicate", []testZipEntry{{"lib/arm64-v8a/libother.so", minimalELF64(183, requiredPageAlignment)}, {"lib/arm64-v8a/libother.so", minimalELF64(183, requiredPageAlignment)}}},
		{"wrong machine", []testZipEntry{{"lib/arm64-v8a/libother.so", minimalELF64(62, requiredPageAlignment)}}},
		{"low alignment", []testZipEntry{{"lib/arm64-v8a/libother.so", minimalELF64(183, 4096)}}},
		{"malformed path", []testZipEntry{{"lib/arm64-v8a/nested/libother.so", minimalELF64(183, requiredPageAlignment)}}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			apk := writeTestAPK(t, test.entries)
			if _, err := inspectAPK(profileKotlinRelease, apk, "arm64-v8a", ""); err == nil {
				t.Fatal("invalid native layout was accepted")
			}
		})
	}
}

type testZipEntry struct {
	name string
	data []byte
}

func writeTestAPK(t *testing.T, entries []testZipEntry) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "app.apk")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	for _, entry := range entries {
		writer, err := archive.Create(entry.name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := writer.Write(entry.data); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func minimalELF64(machine uint16, alignment uint64) []byte {
	data := make([]byte, 64+56)
	copy(data[:16], []byte{0x7f, 'E', 'L', 'F', 2, 1, 1})
	binary.LittleEndian.PutUint16(data[16:18], 3)
	binary.LittleEndian.PutUint16(data[18:20], machine)
	binary.LittleEndian.PutUint32(data[20:24], 1)
	binary.LittleEndian.PutUint64(data[32:40], 64)
	binary.LittleEndian.PutUint16(data[52:54], 64)
	binary.LittleEndian.PutUint16(data[54:56], 56)
	binary.LittleEndian.PutUint16(data[56:58], 1)
	binary.LittleEndian.PutUint32(data[64:68], 1)
	binary.LittleEndian.PutUint64(data[64+48:64+56], alignment)
	return data
}
