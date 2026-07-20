package reprobuild

import (
	"bufio"
	"encoding/base64"
	"errors"
	"os"
	"strconv"
	"strings"

	"golang.org/x/mod/module"
)

const (
	BridgeModulePath          = "github.com/ycfeng/ocdeck-android/frpc-stcp-visitor-go"
	BindDriverModulePath      = BridgeModulePath + "/gomobile-bind"
	ANetCompatModulePath      = BridgeModulePath + "/anetcompat"
	ANetCompatModuleVersion   = "v0.1.0"
	ANetModulePath            = "github.com/wlynxg/anet"
	ANetModuleVersion         = "v0.0.5"
	FRPModulePath             = "github.com/fatedier/frp"
	FRPModuleVersion          = "v0.69.1-p1"
	FRPUpstreamVersion        = "v0.69.1"
	FRPPatchName              = "frp-v0.69.1-p1"
	YamuxModulePath           = "github.com/hashicorp/yamux"
	YamuxModuleVersion        = "v0.1.1"
	YamuxReplacementPath      = "github.com/fatedier/yamux"
	YamuxReplacementVersion   = "v0.0.0-20250825093530-d0154be01cd6"
	localModuleZeroVersion    = "v0.0.0-00010101000000-000000000000"
	expectedBridgeFRPSuffix   = "-frp0.69.1-p1"
	moduleGraphDigestByteSize = 32
)

type Versions struct {
	BridgeVersion  string
	GoVersion      string
	XMobileVersion string
	AndroidAPI     int
	NDKVersion     string
}

type ModuleIdentity struct {
	Path    string
	Version string
}

type ModuleExpectation struct {
	Label       string
	Module      ModuleIdentity
	Replacement *ModuleIdentity
}

func ReadVersions(path string) (Versions, error) {
	file, err := os.Open(path)
	if err != nil {
		return Versions{}, errors.New("read bridge version properties")
	}
	defer file.Close()

	values := make(map[string]string)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		key = strings.TrimSpace(key)
		if !ok || key == "" {
			return Versions{}, errors.New("bridge version properties contain an invalid line")
		}
		if _, exists := values[key]; exists {
			return Versions{}, errors.New("bridge version properties contain a duplicate key")
		}
		values[key] = strings.TrimSpace(value)
	}
	if scanner.Err() != nil {
		return Versions{}, errors.New("read bridge version properties")
	}
	for _, key := range []string{"BRIDGE_VERSION", "GO_VERSION", "XMOBILE_VERSION", "ANDROID_API", "NDK_VERSION"} {
		if values[key] == "" {
			return Versions{}, errors.New("bridge version properties are incomplete")
		}
	}
	androidAPI, err := strconv.Atoi(values["ANDROID_API"])
	if err != nil || androidAPI <= 0 {
		return Versions{}, errors.New("bridge Android API version is invalid")
	}
	versions := Versions{
		BridgeVersion:  values["BRIDGE_VERSION"],
		GoVersion:      values["GO_VERSION"],
		XMobileVersion: values["XMOBILE_VERSION"],
		AndroidAPI:     androidAPI,
		NDKVersion:     values["NDK_VERSION"],
	}
	if err := versions.Validate(); err != nil {
		return Versions{}, err
	}
	return versions, nil
}

func (v Versions) Validate() error {
	if !strings.HasPrefix(v.GoVersion, "go1.") {
		return errors.New("bridge Go version is invalid")
	}
	if module.CanonicalVersion(v.XMobileVersion) != v.XMobileVersion {
		return errors.New("bridge x/mobile version is invalid")
	}
	if _, err := v.BridgeGoModuleVersion(); err != nil {
		return err
	}
	return nil
}

func (v Versions) BridgeGoModuleVersion() (string, error) {
	if !strings.HasSuffix(v.BridgeVersion, expectedBridgeFRPSuffix) {
		return "", errors.New("bridge version does not match the patched frp version")
	}
	base := strings.TrimSuffix(v.BridgeVersion, expectedBridgeFRPSuffix)
	version := "v" + base
	if module.CanonicalVersion(version) != version {
		return "", errors.New("bridge Go module version is invalid")
	}
	return version, nil
}

func (v Versions) ExpectedModules() ([]ModuleExpectation, error) {
	bridgeVersion, err := v.BridgeGoModuleVersion()
	if err != nil {
		return nil, err
	}
	return []ModuleExpectation{
		{
			Label:  "bridge",
			Module: ModuleIdentity{Path: BridgeModulePath, Version: bridgeVersion},
		},
		{
			Label:  "patched frp",
			Module: ModuleIdentity{Path: FRPModulePath, Version: FRPModuleVersion},
		},
		{
			Label:  "anetcompat",
			Module: ModuleIdentity{Path: ANetModulePath, Version: ANetModuleVersion},
			Replacement: &ModuleIdentity{
				Path:    ANetCompatModulePath,
				Version: ANetCompatModuleVersion,
			},
		},
		{
			Label:  "yamux",
			Module: ModuleIdentity{Path: YamuxModulePath, Version: YamuxModuleVersion},
			Replacement: &ModuleIdentity{
				Path:    YamuxReplacementPath,
				Version: YamuxReplacementVersion,
			},
		},
		{
			Label:  "x/mobile",
			Module: ModuleIdentity{Path: "golang.org/x/mobile", Version: v.XMobileVersion},
		},
	}, nil
}

func NoSumDBPatterns() string {
	return strings.Join([]string{BridgeModulePath, FRPModulePath}, ",")
}

func IsStableModuleVersion(version string) bool {
	return version != "" && version != "(devel)" && version != localModuleZeroVersion &&
		module.CanonicalVersion(version) == version
}

func IsValidModuleSum(sum string) bool {
	if !strings.HasPrefix(sum, "h1:") {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(sum, "h1:"))
	return err == nil && len(decoded) == moduleGraphDigestByteSize
}
