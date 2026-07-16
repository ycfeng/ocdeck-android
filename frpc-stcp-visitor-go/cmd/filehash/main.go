package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: filehash <file>")
		os.Exit(2)
	}
	file, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	digest := sha256.New()
	_, copyErr := io.Copy(digest, file)
	closeErr := file.Close()
	if copyErr != nil {
		fmt.Fprintln(os.Stderr, copyErr)
		os.Exit(1)
	}
	if closeErr != nil {
		fmt.Fprintln(os.Stderr, closeErr)
		os.Exit(1)
	}
	fmt.Println(hex.EncodeToString(digest.Sum(nil)))
}
