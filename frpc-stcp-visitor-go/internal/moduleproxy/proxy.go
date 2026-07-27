package moduleproxy

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"

	"golang.org/x/mod/modfile"
	"golang.org/x/mod/module"
	"golang.org/x/mod/sumdb/dirhash"
	modulezip "golang.org/x/mod/zip"

	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

const (
	ConfigRelativePath     = "build/module-proxy.properties"
	ProxyRelativePath      = "build/module-proxy"
	BindModuleRelativePath = "build/gomobile-bind"
)

var proxyTimestamp = time.Date(2000, time.January, 1, 0, 0, 0, 0, time.UTC)

type Config struct {
	ProxyURL      string
	BindPackage   string
	BindModule    string
	NoSumDB       string
	BridgeModule  string
	BridgeVersion string
}

type moduleSpec struct {
	identity module.Version
	goMod    []byte
	files    []modulezip.File
}

type moduleResult struct {
	identity  module.Version
	moduleSum string
	goModSum  string
}

type diskFile struct {
	root string
	path string
}

type memoryFile struct {
	path string
	data []byte
}

type memoryFileInfo struct {
	name string
	size int64
}

func Prepare(root string, versions reprobuild.Versions) (Config, error) {
	if err := versions.Validate(); err != nil {
		return Config{}, err
	}
	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		return Config{}, errors.New("resolve Go bridge project root")
	}
	rootGoMod, err := os.ReadFile(filepath.Join(absoluteRoot, "go.mod"))
	if err != nil {
		return Config{}, errors.New("read Go bridge module definition")
	}
	if err := validatePinnedMobileTools(rootGoMod, versions.XMobileVersion); err != nil {
		return Config{}, err
	}
	rootGoSum, err := os.ReadFile(filepath.Join(absoluteRoot, "go.sum"))
	if err != nil {
		return Config{}, errors.New("read Go bridge module checksums")
	}
	bridgeVersion, err := versions.BridgeGoModuleVersion()
	if err != nil {
		return Config{}, err
	}
	bridgeGoMod, err := formalGoMod(rootGoMod, reprobuild.BridgeModulePath, "")
	if err != nil {
		return Config{}, err
	}
	driverGoMod, err := formalGoMod(rootGoMod, reprobuild.BindDriverModulePath, bridgeVersion)
	if err != nil {
		return Config{}, err
	}
	patchedRoot := filepath.Join(absoluteRoot, "build", reprobuild.FRPPatchName)
	patchedGoMod, err := os.ReadFile(filepath.Join(patchedRoot, "go.mod"))
	if err != nil {
		return Config{}, errors.New("patched frp source is missing; run preparefrp first")
	}
	patchedGoMod, err = formalGoMod(patchedGoMod, reprobuild.FRPModulePath, "")
	if err != nil {
		return Config{}, err
	}
	anetGoMod := []byte("module " + reprobuild.ANetCompatModulePath + "\n\ngo 1.25.0\n")

	bridgeFiles, err := selectedFiles(absoluteRoot, []string{"types.go", "visitor.go"}, bridgeGoMod)
	if err != nil {
		return Config{}, err
	}
	anetFiles, err := selectedFiles(
		filepath.Join(absoluteRoot, "internal", "anetcompat"),
		[]string{"interface.go"},
		anetGoMod,
	)
	if err != nil {
		return Config{}, err
	}
	frpFiles, err := allFiles(patchedRoot, patchedGoMod)
	if err != nil {
		return Config{}, err
	}

	proxyFinal := filepath.Join(absoluteRoot, filepath.FromSlash(ProxyRelativePath))
	proxyTemporary := proxyFinal + ".tmp"
	bindFinal := filepath.Join(absoluteRoot, filepath.FromSlash(BindModuleRelativePath))
	bindTemporary := bindFinal + ".tmp"
	if err := replaceDirectory(proxyTemporary); err != nil {
		return Config{}, err
	}
	if err := replaceDirectory(bindTemporary); err != nil {
		return Config{}, err
	}

	modules := []moduleSpec{
		{
			identity: module.Version{Path: reprobuild.ANetCompatModulePath, Version: reprobuild.ANetCompatModuleVersion},
			goMod:    anetGoMod,
			files:    anetFiles,
		},
		{
			identity: module.Version{Path: reprobuild.FRPModulePath, Version: reprobuild.FRPModuleVersion},
			goMod:    patchedGoMod,
			files:    frpFiles,
		},
		{
			identity: module.Version{Path: reprobuild.BridgeModulePath, Version: bridgeVersion},
			goMod:    bridgeGoMod,
			files:    bridgeFiles,
		},
	}
	results := make([]moduleResult, 0, len(modules))
	for _, spec := range modules {
		result, err := writeModuleVersion(proxyTemporary, spec)
		if err != nil {
			return Config{}, err
		}
		results = append(results, result)
	}

	if err := os.WriteFile(filepath.Join(bindTemporary, "go.mod"), driverGoMod, 0o644); err != nil {
		return Config{}, errors.New("write gomobile bind module definition")
	}
	goSum, err := mergedGoSum(rootGoSum, results)
	if err != nil {
		return Config{}, err
	}
	if err := os.WriteFile(filepath.Join(bindTemporary, "go.sum"), goSum, 0o644); err != nil {
		return Config{}, errors.New("write gomobile bind module checksums")
	}
	if err := installDirectory(proxyTemporary, proxyFinal); err != nil {
		return Config{}, err
	}
	if err := installDirectory(bindTemporary, bindFinal); err != nil {
		return Config{}, err
	}

	absoluteProxy, err := filepath.Abs(proxyFinal)
	if err != nil {
		return Config{}, errors.New("resolve generated module proxy")
	}
	proxyURL, err := fileURLForPath(absoluteProxy, runtime.GOOS)
	if err != nil {
		return Config{}, err
	}
	config := Config{
		ProxyURL:      proxyURL,
		BindPackage:   reprobuild.BridgeModulePath,
		BindModule:    BindModuleRelativePath,
		NoSumDB:       reprobuild.NoSumDBPatterns(),
		BridgeModule:  reprobuild.BridgeModulePath,
		BridgeVersion: bridgeVersion,
	}
	if err := writeConfig(filepath.Join(absoluteRoot, filepath.FromSlash(ConfigRelativePath)), config); err != nil {
		return Config{}, err
	}
	return config, nil
}

func formalGoMod(source []byte, modulePath string, bridgeVersion string) ([]byte, error) {
	parsed, err := modfile.Parse("go.mod", source, nil)
	if err != nil {
		return nil, errors.New("parse Go module definition")
	}
	if err := parsed.AddModuleStmt(modulePath); err != nil {
		return nil, errors.New("set versioned Go module path")
	}
	if modulePath != reprobuild.FRPModulePath {
		if err := parsed.AddRequire(reprobuild.FRPModulePath, reprobuild.FRPModuleVersion); err != nil {
			return nil, errors.New("pin patched frp module")
		}
	}
	if bridgeVersion != "" {
		if err := parsed.AddRequire(reprobuild.BridgeModulePath, bridgeVersion); err != nil {
			return nil, errors.New("pin versioned bridge module")
		}
	}
	for _, replacement := range append([]*modfile.Replace(nil), parsed.Replace...) {
		if replacement.Old.Path == reprobuild.ANetModulePath || replacement.Old.Path == reprobuild.YamuxModulePath {
			if err := parsed.DropReplace(replacement.Old.Path, replacement.Old.Version); err != nil {
				return nil, errors.New("replace Go module graph override")
			}
		}
	}
	parsed.Cleanup()
	if err := parsed.AddReplace(
		reprobuild.ANetModulePath,
		reprobuild.ANetModuleVersion,
		reprobuild.ANetCompatModulePath,
		reprobuild.ANetCompatModuleVersion,
	); err != nil {
		return nil, errors.New("pin anetcompat module")
	}
	if err := parsed.AddReplace(
		reprobuild.YamuxModulePath,
		"",
		reprobuild.YamuxReplacementPath,
		reprobuild.YamuxReplacementVersion,
	); err != nil {
		return nil, errors.New("pin yamux module")
	}
	for _, replacement := range parsed.Replace {
		if replacement.New.Version == "" || !reprobuild.IsStableModuleVersion(replacement.New.Version) {
			label := "unexpected"
			switch replacement.Old.Path {
			case reprobuild.ANetModulePath:
				label = "anetcompat"
			case reprobuild.YamuxModulePath:
				label = "yamux"
			}
			return nil, fmt.Errorf("formal Go module graph contains a local or unversioned %s replacement", label)
		}
	}
	parsed.Cleanup()
	parsed.SortBlocks()
	formatted, err := parsed.Format()
	if err != nil {
		return nil, errors.New("format formal Go module definition")
	}
	return formatted, nil
}

func validatePinnedMobileTools(source []byte, expectedVersion string) error {
	parsed, err := modfile.Parse("go.mod", source, nil)
	if err != nil {
		return errors.New("parse Go bridge module definition")
	}
	foundVersion := ""
	for _, requirement := range parsed.Require {
		if requirement.Mod.Path == "golang.org/x/mobile" {
			foundVersion = requirement.Mod.Version
			break
		}
	}
	if foundVersion != expectedVersion {
		return errors.New("Go bridge module does not match the pinned x/mobile version")
	}
	for _, tool := range parsed.Tool {
		if tool.Path == "golang.org/x/mobile/cmd/gobind" {
			return nil
		}
	}
	return errors.New("Go bridge module is missing the pinned gobind tool directive")
}

func selectedFiles(root string, paths []string, goMod []byte) ([]modulezip.File, error) {
	files := make([]modulezip.File, 0, len(paths)+1)
	for _, path := range paths {
		fullPath := filepath.Join(root, filepath.FromSlash(path))
		info, err := os.Lstat(fullPath)
		if err != nil || !info.Mode().IsRegular() {
			return nil, errors.New("required module source file is missing")
		}
		files = append(files, diskFile{root: root, path: path})
	}
	files = append(files, memoryFile{path: "go.mod", data: goMod})
	sort.Slice(files, func(i, j int) bool { return files[i].Path() < files[j].Path() })
	return files, nil
}

func allFiles(root string, goMod []byte) ([]modulezip.File, error) {
	files := []modulezip.File{memoryFile{path: "go.mod", data: goMod}}
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return errors.New("walk module source")
		}
		if entry.IsDir() {
			if path != root && (entry.Name() == ".git" || entry.Name() == ".hg" || entry.Name() == ".svn") {
				return filepath.SkipDir
			}
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return errors.New("resolve module source file")
		}
		relative = filepath.ToSlash(relative)
		if relative == "go.mod" {
			return nil
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return errors.New("module source contains an unsupported file")
		}
		files = append(files, diskFile{root: root, path: relative})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Path() < files[j].Path() })
	return files, nil
}

func writeModuleVersion(proxyRoot string, spec moduleSpec) (moduleResult, error) {
	escapedPath, err := module.EscapePath(spec.identity.Path)
	if err != nil {
		return moduleResult{}, errors.New("local proxy module path is invalid")
	}
	escapedVersion, err := module.EscapeVersion(spec.identity.Version)
	if err != nil || module.CanonicalVersion(spec.identity.Version) != spec.identity.Version {
		return moduleResult{}, errors.New("local proxy module version is invalid")
	}
	versionDirectory := filepath.Join(proxyRoot, filepath.FromSlash(escapedPath), "@v")
	if err := os.MkdirAll(versionDirectory, 0o755); err != nil {
		return moduleResult{}, errors.New("create local module proxy directory")
	}
	modPath := filepath.Join(versionDirectory, escapedVersion+".mod")
	if err := os.WriteFile(modPath, spec.goMod, 0o644); err != nil {
		return moduleResult{}, errors.New("write local proxy module definition")
	}
	infoData, err := json.Marshal(struct {
		Version string
		Time    time.Time
	}{Version: spec.identity.Version, Time: proxyTimestamp})
	if err != nil {
		return moduleResult{}, errors.New("encode local proxy module metadata")
	}
	infoData = append(infoData, '\n')
	if err := os.WriteFile(filepath.Join(versionDirectory, escapedVersion+".info"), infoData, 0o644); err != nil {
		return moduleResult{}, errors.New("write local proxy module metadata")
	}
	if err := os.WriteFile(filepath.Join(versionDirectory, "list"), []byte(spec.identity.Version+"\n"), 0o644); err != nil {
		return moduleResult{}, errors.New("write local proxy module version list")
	}
	zipPath := filepath.Join(versionDirectory, escapedVersion+".zip")
	files := append([]modulezip.File(nil), spec.files...)
	sort.Slice(files, func(i, j int) bool { return files[i].Path() < files[j].Path() })
	archive, err := os.Create(zipPath)
	if err != nil {
		return moduleResult{}, errors.New("create local proxy module archive")
	}
	createErr := modulezip.Create(archive, spec.identity, files)
	closeErr := archive.Close()
	if createErr != nil || closeErr != nil {
		return moduleResult{}, errors.New("create conforming local proxy module archive")
	}
	if _, err := modulezip.CheckZip(spec.identity, zipPath); err != nil {
		return moduleResult{}, errors.New("validate local proxy module archive")
	}
	moduleSum, err := dirhash.HashZip(zipPath, dirhash.Hash1)
	if err != nil {
		return moduleResult{}, errors.New("hash local proxy module archive")
	}
	goModSum, err := dirhash.Hash1([]string{"go.mod"}, func(string) (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(spec.goMod)), nil
	})
	if err != nil {
		return moduleResult{}, errors.New("hash local proxy module definition")
	}
	return moduleResult{identity: spec.identity, moduleSum: moduleSum, goModSum: goModSum}, nil
}

func mergedGoSum(existing []byte, modules []moduleResult) ([]byte, error) {
	lines := make(map[string]struct{})
	for _, line := range strings.Split(strings.ReplaceAll(string(existing), "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			lines[line] = struct{}{}
		}
	}
	for _, result := range modules {
		if !reprobuild.IsValidModuleSum(result.moduleSum) || !reprobuild.IsValidModuleSum(result.goModSum) {
			return nil, errors.New("generated local module checksum is invalid")
		}
		lines[fmt.Sprintf("%s %s %s", result.identity.Path, result.identity.Version, result.moduleSum)] = struct{}{}
		lines[fmt.Sprintf("%s %s/go.mod %s", result.identity.Path, result.identity.Version, result.goModSum)] = struct{}{}
	}
	sorted := make([]string, 0, len(lines))
	for line := range lines {
		sorted = append(sorted, line)
	}
	sort.Strings(sorted)
	return []byte(strings.Join(sorted, "\n") + "\n"), nil
}

func writeConfig(path string, config Config) error {
	data := strings.Join([]string{
		"SCHEMA_VERSION=1",
		"PROXY_URL=" + config.ProxyURL,
		"BIND_PACKAGE=" + config.BindPackage,
		"BIND_MODULE_DIR=" + config.BindModule,
		"GONOSUMDB=" + config.NoSumDB,
		"BRIDGE_MODULE=" + config.BridgeModule,
		"BRIDGE_MODULE_VERSION=" + config.BridgeVersion,
		"",
	}, "\n")
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		return errors.New("write local module proxy configuration")
	}
	return nil
}

func replaceDirectory(path string) error {
	if err := os.RemoveAll(path); err != nil {
		return errors.New("clear generated module build directory")
	}
	if err := os.MkdirAll(path, 0o755); err != nil {
		return errors.New("create generated module build directory")
	}
	return nil
}

func installDirectory(source string, destination string) error {
	if err := os.RemoveAll(destination); err != nil {
		return errors.New("replace generated module build directory")
	}
	if err := os.Rename(source, destination); err != nil {
		return errors.New("install generated module build directory")
	}
	return nil
}

func fileURLForPath(path string, goos string) (string, error) {
	normalized := strings.ReplaceAll(path, "\\", "/")
	var value *url.URL
	if goos == "windows" {
		switch {
		case len(normalized) >= 3 && normalized[1] == ':' && normalized[2] == '/':
			value = &url.URL{Scheme: "file", Path: "/" + normalized}
		case strings.HasPrefix(normalized, "//"):
			parts := strings.SplitN(strings.TrimPrefix(normalized, "//"), "/", 2)
			if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
				return "", errors.New("module proxy path is not an absolute Windows path")
			}
			value = &url.URL{Scheme: "file", Host: parts[0], Path: "/" + parts[1]}
		default:
			return "", errors.New("module proxy path is not an absolute Windows path")
		}
	} else {
		if !strings.HasPrefix(normalized, "/") {
			return "", errors.New("module proxy path is not absolute")
		}
		value = &url.URL{Scheme: "file", Path: normalized}
	}
	encoded := value.String()
	encoded = strings.ReplaceAll(encoded, ",", "%2C")
	encoded = strings.ReplaceAll(encoded, "|", "%7C")
	return encoded, nil
}

func (f diskFile) Path() string {
	return f.path
}

func (f diskFile) Lstat() (os.FileInfo, error) {
	info, err := os.Lstat(filepath.Join(f.root, filepath.FromSlash(f.path)))
	if err != nil {
		return nil, errors.New("inspect module source file")
	}
	return info, nil
}

func (f diskFile) Open() (io.ReadCloser, error) {
	file, err := os.Open(filepath.Join(f.root, filepath.FromSlash(f.path)))
	if err != nil {
		return nil, errors.New("open module source file")
	}
	return file, nil
}

func (f memoryFile) Path() string {
	return f.path
}

func (f memoryFile) Lstat() (os.FileInfo, error) {
	return memoryFileInfo{name: filepath.Base(f.path), size: int64(len(f.data))}, nil
}

func (f memoryFile) Open() (io.ReadCloser, error) {
	return io.NopCloser(bytes.NewReader(f.data)), nil
}

func (i memoryFileInfo) Name() string       { return i.name }
func (i memoryFileInfo) Size() int64        { return i.size }
func (i memoryFileInfo) Mode() os.FileMode  { return 0o644 }
func (i memoryFileInfo) ModTime() time.Time { return proxyTimestamp }
func (i memoryFileInfo) IsDir() bool        { return false }
func (i memoryFileInfo) Sys() any           { return nil }

func readZipEntry(path string, name string) ([]byte, error) {
	archive, err := zip.OpenReader(path)
	if err != nil {
		return nil, err
	}
	defer archive.Close()
	for _, entry := range archive.File {
		if entry.Name != name {
			continue
		}
		reader, err := entry.Open()
		if err != nil {
			return nil, err
		}
		data, readErr := io.ReadAll(reader)
		closeErr := reader.Close()
		return data, errors.Join(readErr, closeErr)
	}
	return nil, os.ErrNotExist
}
