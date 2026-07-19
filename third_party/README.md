# Third-Party Inventory

This directory is the manually maintained source and license inventory for OC Deck.

- `components.toml` records shipped Android/JVM families, build tooling relevant to redistribution, native Go modules compiled into the GoMobile bridge, and bundled assets.
- `sources/opencode-audio.json` maps every bundled AAC file to the fixed OpenCode source commit and records local SHA-256 values.
- `sources/opencode-ui-assets.json` maps verified OpenCode-derived Android vectors to fixed upstream source symbols and records local SHA-256 values.
- `sources/frp.json` records the fixed frp upstream commit, module hashes, downstream patch hash, and all modified or added files.
- `sources/gradle-wrapper.json` records the Gradle distribution and wrapper JAR hashes.
- `licenses/` contains the license texts needed by the inventoried components, including licenses for code embedded inside a dependency JAR.
- The human-readable distribution notice is `../THIRD_PARTY_NOTICES.txt`.
- Application APKs embed these notices and licenses under `assets/legal/`. The GoMobile bridge AAR embeds the project notices, individual licenses, exact Java API, and bridge/frp provenance under `META-INF/OCDECK/`.

## Audit Basis

The current inventory was reviewed for OC Deck `0.2.0` and bridge `0.3.7-frp0.69.1-p1` on 2026-07-19 using:

```powershell
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath --console=plain
```

Four Android Go dependency walks are performed for `android/arm64`, `android/arm` with `GOARM=7`, `android/386`, and `android/amd64`. The checked inventory is their union. The repository audit runs the equivalent fixed-modfile commands automatically:

```powershell
python .github/scripts/audit-third-party.py
```

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath <path>
```

The AndroidX entry uses resolved artifact-family selectors rather than pretending that one Maven coordinate represents the whole Jetpack graph. The separately packaged DataStore external protobuf artifact is recorded under BSD-3-Clause rather than being folded into the usual AndroidX Apache-2.0 family. The Go entries list the union of modules reached by all four Android Go targets above. `github.com/wlynxg/anet` is not inventoried as distributed upstream code because the build replaces it with OC Deck's local `internal/anetcompat` implementation.

OpenCode-derived vector attribution is based on verified path geometry, not on the local `ic_opencode_*` filename prefix alone. Copyright clearance does not replace trademark review. A provider logo that has been selected for removal must be absent from the final APK before release rather than merely omitted from this inventory.

## Update Rules

When a dependency, Gradle wrapper, bridge version, frp patch, or bundled asset changes:

1. Re-resolve the release runtime graph and Android Go dependency graph.
2. Recompute hashes from local distributed bytes.
3. Update the matching source JSON and `components.toml`.
4. Add any newly required license text and attribution to `THIRD_PARTY_NOTICES.txt`.
5. Verify that no removed asset remains described as distributed.
6. Review the generated APK/AAR contents before release; source declarations alone do not prove final-package contents.

This inventory is not a substitute for legal review. It intentionally avoids assigning copyright where the fixed upstream license file does not state one.
