package main

import (
	"fmt"
	"os"

	"opencode-frpc-stcp-visitor/internal/contractfixture"
)

func main() {
	if len(os.Args) != 2 || (os.Args[1] != "check" && os.Args[1] != "write") {
		fmt.Fprintln(os.Stderr, "usage: contractfixture check|write")
		os.Exit(2)
	}

	var (
		result contractfixture.Result
		err    error
	)
	if os.Args[1] == "write" {
		result, err = contractfixture.Write()
	} else {
		result, err = contractfixture.Check()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("contract fixtures %s: %d entries in %s\n", os.Args[1], result.Entries, result.Directory)
}
