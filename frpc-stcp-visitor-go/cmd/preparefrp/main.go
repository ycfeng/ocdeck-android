package main

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const downstreamName = "frp-v0.69.1-p1"

var hunkHeaderPattern = regexp.MustCompile(`^@@ -([0-9]+)(?:,[0-9]+)? \+([0-9]+)(?:,[0-9]+)? @@(.*)$`)

type manifest struct {
	Module        string            `json:"module"`
	Version       string            `json:"version"`
	ModuleSum     string            `json:"moduleSum"`
	GoModSum      string            `json:"goModSum"`
	OriginHash    string            `json:"originHash"`
	ZipSHA256     string            `json:"zipSha256"`
	UpstreamFiles map[string]string `json:"upstreamFiles"`
	Patches       []string          `json:"patches"`
	GofmtFiles    []string          `json:"gofmtFiles"`
}

type moduleDownload struct {
	Path     string `json:"Path"`
	Version  string `json:"Version"`
	GoMod    string `json:"GoMod"`
	Zip      string `json:"Zip"`
	Sum      string `json:"Sum"`
	GoModSum string `json:"GoModSum"`
	Origin   *struct {
		Hash string `json:"Hash"`
	} `json:"Origin"`
	Error *struct {
		Err string `json:"Err"`
	} `json:"Error"`
}

type provenance struct {
	Downstream    string            `json:"downstream"`
	Module        string            `json:"module"`
	Version       string            `json:"version"`
	ModuleSum     string            `json:"moduleSum"`
	GoModSum      string            `json:"goModSum"`
	OriginHash    string            `json:"originHash"`
	ZipSHA256     string            `json:"zipSha256"`
	PatchSHA256   map[string]string `json:"patchSha256"`
	PatchedFiles  []string          `json:"patchedFiles"`
	ModifiedFiles []string          `json:"modifiedFiles"`
	AddedFiles    []string          `json:"addedFiles"`
}

type patchFiles struct {
	modified []string
	added    []string
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	root, err := os.Getwd()
	if err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(root, "go.mod")); err != nil {
		return errors.New("preparefrp must run from frpc-stcp-visitor-go")
	}

	downstreamDir := filepath.Join(root, "downstream", downstreamName)
	manifestPath := filepath.Join(downstreamDir, "manifest.json")
	m, err := readJSON[manifest](manifestPath)
	if err != nil {
		return fmt.Errorf("read downstream manifest: %w", err)
	}

	download, err := downloadModule(root, m.Module, m.Version)
	if err != nil {
		return err
	}
	if download.Path != m.Module || download.Version != m.Version {
		return fmt.Errorf("unexpected upstream module: %s@%s", download.Path, download.Version)
	}
	if download.Sum != m.ModuleSum || download.GoModSum != m.GoModSum {
		return fmt.Errorf("upstream Go checksum mismatch for %s@%s", m.Module, m.Version)
	}
	if download.Origin != nil && download.Origin.Hash != "" && download.Origin.Hash != m.OriginHash {
		return fmt.Errorf("upstream origin hash mismatch: got %s", download.Origin.Hash)
	}
	if err := verifyFileSHA256(download.Zip, m.ZipSHA256); err != nil {
		return fmt.Errorf("verify upstream module zip: %w", err)
	}

	buildDir := filepath.Join(root, "build")
	patchedDir := filepath.Join(buildDir, downstreamName)
	if err := os.RemoveAll(patchedDir); err != nil {
		return fmt.Errorf("clear patched source: %w", err)
	}
	if err := os.MkdirAll(patchedDir, 0o755); err != nil {
		return fmt.Errorf("create patched source directory: %w", err)
	}
	if err := extractModuleZip(download.Zip, m.Module+"@"+m.Version+"/", patchedDir); err != nil {
		return fmt.Errorf("extract upstream module: %w", err)
	}

	paths := make([]string, 0, len(m.UpstreamFiles))
	for path := range m.UpstreamFiles {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		if err := verifyFileSHA256(filepath.Join(patchedDir, filepath.FromSlash(path)), m.UpstreamFiles[path]); err != nil {
			return fmt.Errorf("verify upstream file %s: %w", path, err)
		}
	}

	patchHashes := make(map[string]string, len(m.Patches))
	modifiedFiles := make(map[string]struct{})
	addedFiles := make(map[string]struct{})
	for _, patchName := range m.Patches {
		patchPath := filepath.Join(downstreamDir, patchName)
		changed, err := readPatchFiles(patchPath)
		if err != nil {
			return fmt.Errorf("read changed files from patch %s: %w", patchName, err)
		}
		for _, path := range changed.modified {
			if _, ok := m.UpstreamFiles[path]; !ok {
				return fmt.Errorf("patch %s modifies %s without a locked upstream hash", patchName, path)
			}
			modifiedFiles[path] = struct{}{}
		}
		for _, path := range changed.added {
			if _, ok := m.UpstreamFiles[path]; ok {
				return fmt.Errorf("patch %s marks locked upstream file %s as added", patchName, path)
			}
			addedFiles[path] = struct{}{}
		}
		hash, err := fileSHA256(patchPath)
		if err != nil {
			return fmt.Errorf("hash patch %s: %w", patchName, err)
		}
		patchHashes[patchName] = hash
		normalizedPatchPath := filepath.Join(buildDir, "normalized-"+patchName)
		if err := normalizePatch(patchPath, normalizedPatchPath); err != nil {
			return fmt.Errorf("normalize patch %s: %w", patchName, err)
		}
		cmd := exec.Command("git", "apply", "--unidiff-zero", "--whitespace=nowarn", normalizedPatchPath)
		cmd.Dir = patchedDir
		cmd.Env = append(os.Environ(), "GIT_CEILING_DIRECTORIES="+filepath.Dir(patchedDir))
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("apply patch %s: %w: %s", patchName, err, strings.TrimSpace(string(output)))
		}
	}
	for path := range m.UpstreamFiles {
		if _, ok := modifiedFiles[path]; !ok {
			return fmt.Errorf("locked upstream file %s is not modified by the patch stack", path)
		}
	}
	modifiedPaths := sortedSet(modifiedFiles)
	addedPaths := sortedSet(addedFiles)
	patchedPaths := append(append(make([]string, 0, len(modifiedPaths)+len(addedPaths)), modifiedPaths...), addedPaths...)
	sort.Strings(patchedPaths)
	if len(m.GofmtFiles) > 0 {
		gofmtArgs := make([]string, 0, len(m.GofmtFiles)+1)
		gofmtArgs = append(gofmtArgs, "-w")
		for _, path := range m.GofmtFiles {
			gofmtArgs = append(gofmtArgs, filepath.Join(patchedDir, filepath.FromSlash(path)))
		}
		cmd := exec.Command("gofmt", gofmtArgs...)
		if output, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("format patched source: %w: %s", err, strings.TrimSpace(string(output)))
		}
	}

	modfilePath := filepath.Join(buildDir, "frp-patched.mod")
	if err := writePatchedModfile(root, patchedDir, modfilePath); err != nil {
		return err
	}

	prov := provenance{
		Downstream:    downstreamName,
		Module:        m.Module,
		Version:       m.Version,
		ModuleSum:     m.ModuleSum,
		GoModSum:      m.GoModSum,
		OriginHash:    m.OriginHash,
		ZipSHA256:     m.ZipSHA256,
		PatchSHA256:   patchHashes,
		PatchedFiles:  patchedPaths,
		ModifiedFiles: modifiedPaths,
		AddedFiles:    addedPaths,
	}
	provenancePath := filepath.Join(buildDir, "frp-patch-provenance.json")
	if err := writeJSON(provenancePath, prov); err != nil {
		return fmt.Errorf("write patch provenance: %w", err)
	}

	fmt.Printf("patched frp: %s@%s (%s)\n", m.Module, m.Version, downstreamName)
	fmt.Printf("modfile: %s\n", modfilePath)
	fmt.Printf("provenance: %s\n", provenancePath)
	return nil
}

func readPatchFiles(path string) (patchFiles, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return patchFiles{}, err
	}
	lines := strings.Split(string(data), "\n")
	modified := make(map[string]struct{})
	added := make(map[string]struct{})
	for index := 0; index < len(lines); index++ {
		if !strings.HasPrefix(lines[index], "diff --git a/") {
			continue
		}
		parts := strings.SplitN(strings.TrimPrefix(lines[index], "diff --git "), " ", 2)
		if len(parts) != 2 || !strings.HasPrefix(parts[0], "a/") || !strings.HasPrefix(parts[1], "b/") {
			return patchFiles{}, fmt.Errorf("invalid diff header %q", lines[index])
		}
		oldPath := strings.TrimPrefix(parts[0], "a/")
		newPath := strings.TrimPrefix(parts[1], "b/")
		if oldPath != newPath {
			return patchFiles{}, fmt.Errorf("renamed paths are not supported: %s -> %s", oldPath, newPath)
		}
		isAdded := false
		foundOldHeader := false
		foundNewHeader := false
		for scan := index + 1; scan < len(lines) && !strings.HasPrefix(lines[scan], "diff --git "); scan++ {
			switch {
			case lines[scan] == "--- /dev/null":
				isAdded = true
				foundOldHeader = true
			case lines[scan] == "--- a/"+oldPath:
				foundOldHeader = true
			case lines[scan] == "+++ b/"+newPath:
				foundNewHeader = true
			}
		}
		if !foundOldHeader || !foundNewHeader {
			return patchFiles{}, fmt.Errorf("missing file headers for %s", newPath)
		}
		if isAdded {
			added[newPath] = struct{}{}
		} else {
			modified[newPath] = struct{}{}
		}
	}
	if len(modified) == 0 && len(added) == 0 {
		return patchFiles{}, errors.New("patch contains no file changes")
	}
	return patchFiles{modified: sortedSet(modified), added: sortedSet(added)}, nil
}

func sortedSet(values map[string]struct{}) []string {
	result := make([]string, 0, len(values))
	for value := range values {
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func normalizePatch(sourcePath string, destinationPath string) error {
	data, err := os.ReadFile(sourcePath)
	if err != nil {
		return err
	}
	hadTrailingNewline := len(data) > 0 && data[len(data)-1] == '\n'
	lines := strings.Split(strings.TrimSuffix(string(data), "\n"), "\n")
	for i := 0; i < len(lines); i++ {
		match := hunkHeaderPattern.FindStringSubmatch(lines[i])
		if match == nil {
			continue
		}
		oldCount := 0
		newCount := 0
		for j := i + 1; j < len(lines); j++ {
			line := lines[j]
			if strings.HasPrefix(line, "@@ ") || strings.HasPrefix(line, "diff --git ") {
				break
			}
			if line == `\ No newline at end of file` || line == "" {
				continue
			}
			switch line[0] {
			case ' ':
				oldCount++
				newCount++
			case '-':
				oldCount++
			case '+':
				newCount++
			}
		}
		lines[i] = fmt.Sprintf("@@ -%s,%d +%s,%d @@%s", match[1], oldCount, match[2], newCount, match[3])
	}
	output := strings.Join(lines, "\n")
	if hadTrailingNewline {
		output += "\n"
	}
	return os.WriteFile(destinationPath, []byte(output), 0o644)
}

func downloadModule(root string, module string, version string) (moduleDownload, error) {
	cmd := exec.Command("go", "mod", "download", "-json", module+"@"+version)
	cmd.Dir = root
	output, err := cmd.Output()
	if err != nil {
		return moduleDownload{}, fmt.Errorf("download upstream module: %w", err)
	}
	var result moduleDownload
	if err := json.Unmarshal(output, &result); err != nil {
		return result, fmt.Errorf("decode go mod download output: %w", err)
	}
	if result.Error != nil {
		return result, errors.New(result.Error.Err)
	}
	return result, nil
}

func extractModuleZip(zipPath string, prefix string, destination string) error {
	archive, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer archive.Close()

	for _, file := range archive.File {
		if !strings.HasPrefix(file.Name, prefix) {
			return fmt.Errorf("unexpected zip entry %q", file.Name)
		}
		relative := strings.TrimPrefix(file.Name, prefix)
		if relative == "" {
			continue
		}
		target := filepath.Join(destination, filepath.FromSlash(relative))
		cleanDestination := filepath.Clean(destination) + string(os.PathSeparator)
		if !strings.HasPrefix(filepath.Clean(target)+string(os.PathSeparator), cleanDestination) {
			return fmt.Errorf("unsafe zip entry %q", file.Name)
		}
		if file.FileInfo().IsDir() {
			if err := os.MkdirAll(target, file.Mode()); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		reader, err := file.Open()
		if err != nil {
			return err
		}
		writer, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, file.Mode())
		if err != nil {
			reader.Close()
			return err
		}
		_, copyErr := io.Copy(writer, reader)
		closeErr := errors.Join(writer.Close(), reader.Close())
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func writePatchedModfile(root string, patchedDir string, modfilePath string) error {
	goMod, err := os.ReadFile(filepath.Join(root, "go.mod"))
	if err != nil {
		return fmt.Errorf("read go.mod: %w", err)
	}
	replacement := "\nreplace github.com/fatedier/frp => " + filepath.ToSlash(patchedDir) + "\n"
	if err := os.WriteFile(modfilePath, append(goMod, replacement...), 0o644); err != nil {
		return fmt.Errorf("write patched modfile: %w", err)
	}
	goSum, err := os.ReadFile(filepath.Join(root, "go.sum"))
	if err != nil {
		return fmt.Errorf("read go.sum: %w", err)
	}
	sumPath := strings.TrimSuffix(modfilePath, filepath.Ext(modfilePath)) + ".sum"
	if err := os.WriteFile(sumPath, goSum, 0o644); err != nil {
		return fmt.Errorf("write patched sum file: %w", err)
	}
	return nil
}

func verifyFileSHA256(path string, expected string) error {
	actual, err := fileSHA256(path)
	if err != nil {
		return err
	}
	if actual != strings.ToLower(expected) {
		return fmt.Errorf("SHA-256 mismatch: got %s, want %s", actual, strings.ToLower(expected))
	}
	return nil
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func readJSON[T any](path string) (T, error) {
	var value T
	data, err := os.ReadFile(path)
	if err != nil {
		return value, err
	}
	return value, json.Unmarshal(data, &value)
}

func writeJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	return os.WriteFile(path, data, 0o644)
}
