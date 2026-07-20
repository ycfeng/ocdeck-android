package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"opencode-frpc-stcp-visitor/internal/moduleproxy"
	"opencode-frpc-stcp-visitor/internal/reprobuild"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	root, err := os.Getwd()
	if err != nil {
		return errors.New("resolve Go bridge project root")
	}
	if _, err := os.Stat(filepath.Join(root, "go.mod")); err != nil {
		return errors.New("preparemoduleproxy must run from frpc-stcp-visitor-go")
	}
	versions, err := reprobuild.ReadVersions(filepath.Join(root, "bridge-versions.properties"))
	if err != nil {
		return err
	}
	config, err := moduleproxy.Prepare(root, versions)
	if err != nil {
		return err
	}
	fmt.Printf("versioned bridge module: %s@%s\n", config.BridgeModule, config.BridgeVersion)
	fmt.Println("local Go module proxy prepared")
	return nil
}
