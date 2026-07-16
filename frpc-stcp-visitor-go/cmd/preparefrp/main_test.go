package main

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestReadPatchFilesClassifiesModifiedAndAddedFiles(t *testing.T) {
	patch := `diff --git a/client/control.go b/client/control.go
--- a/client/control.go
+++ b/client/control.go
@@ -1 +1 @@
-old
+new
diff --git a/client/runtime_state.go b/client/runtime_state.go
--- /dev/null
+++ b/client/runtime_state.go
@@ -0,0 +1 @@
+package client
`
	path := filepath.Join(t.TempDir(), "change.patch")
	if err := os.WriteFile(path, []byte(patch), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := readPatchFiles(path)
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"client/control.go"}; !reflect.DeepEqual(got.modified, want) {
		t.Fatalf("modified = %v, want %v", got.modified, want)
	}
	if want := []string{"client/runtime_state.go"}; !reflect.DeepEqual(got.added, want) {
		t.Fatalf("added = %v, want %v", got.added, want)
	}
}
