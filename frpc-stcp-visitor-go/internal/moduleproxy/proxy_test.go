package moduleproxy

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/mod/module"
	modulezip "golang.org/x/mod/zip"
)

func TestWriteModuleVersionIsDeterministicAndConformant(t *testing.T) {
	identity := module.Version{Path: "example.com/repro/module", Version: "v1.2.3"}
	goMod := []byte("module example.com/repro/module\n\ngo 1.25.0\n")
	firstRoot := t.TempDir()
	secondRoot := t.TempDir()
	first := moduleSpec{
		identity: identity,
		goMod:    goMod,
		files: []modulezip.File{
			memoryFile{path: "z.go", data: []byte("package module\n")},
			memoryFile{path: "go.mod", data: goMod},
			memoryFile{path: "a.go", data: []byte("package module\n")},
		},
	}
	second := moduleSpec{
		identity: identity,
		goMod:    goMod,
		files: []modulezip.File{
			memoryFile{path: "a.go", data: []byte("package module\n")},
			memoryFile{path: "go.mod", data: goMod},
			memoryFile{path: "z.go", data: []byte("package module\n")},
		},
	}
	firstResult, err := writeModuleVersion(firstRoot, first)
	if err != nil {
		t.Fatal(err)
	}
	secondResult, err := writeModuleVersion(secondRoot, second)
	if err != nil {
		t.Fatal(err)
	}
	if firstResult.moduleSum != secondResult.moduleSum || firstResult.goModSum != secondResult.goModSum {
		t.Fatal("module checksums differ")
	}
	firstZip := proxyZipPath(t, firstRoot, identity)
	secondZip := proxyZipPath(t, secondRoot, identity)
	firstBytes, err := os.ReadFile(firstZip)
	if err != nil {
		t.Fatal(err)
	}
	secondBytes, err := os.ReadFile(secondZip)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(firstBytes, secondBytes) {
		t.Fatal("module ZIP bytes differ")
	}
	if _, err := modulezip.CheckZip(identity, firstZip); err != nil {
		t.Fatalf("module ZIP is not conformant: %v", err)
	}
	prefix := identity.Path + "@" + identity.Version + "/"
	data, err := readZipEntry(firstZip, prefix+"go.mod")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data, goMod) {
		t.Fatal("module ZIP go.mod differs")
	}
}

func TestFileURLForWindowsDrive(t *testing.T) {
	got, err := fileURLForPath(`E:\work tree\proxy`, "windows")
	if err != nil {
		t.Fatal(err)
	}
	if got != "file:///E:/work%20tree/proxy" {
		t.Fatalf("URL = %q", got)
	}
}

func TestFileURLRejectsRelativePathWithoutEchoingIt(t *testing.T) {
	const secretPath = `private\secret\proxy`
	_, err := fileURLForPath(secretPath, "windows")
	if err == nil {
		t.Fatal("expected relative path to fail")
	}
	if bytes.Contains([]byte(err.Error()), []byte("secret")) {
		t.Fatal("error leaked the rejected path")
	}
}

func TestValidatePinnedMobileTools(t *testing.T) {
	goMod := []byte(`module example.com/bridge

go 1.25.0

require golang.org/x/mobile v0.0.0-20260611195102-4dd8f1dbf5d2

tool golang.org/x/mobile/cmd/gobind
`)
	if err := validatePinnedMobileTools(goMod, "v0.0.0-20260611195102-4dd8f1dbf5d2"); err != nil {
		t.Fatal(err)
	}
	if err := validatePinnedMobileTools(goMod, "v0.0.0-20260709172247-6129f5bee9d5"); err == nil {
		t.Fatal("expected mismatched x/mobile version to fail")
	}
}

func proxyZipPath(t *testing.T, root string, identity module.Version) string {
	t.Helper()
	escapedPath, err := module.EscapePath(identity.Path)
	if err != nil {
		t.Fatal(err)
	}
	escapedVersion, err := module.EscapeVersion(identity.Version)
	if err != nil {
		t.Fatal(err)
	}
	return filepath.Join(root, filepath.FromSlash(escapedPath), "@v", escapedVersion+".zip")
}
