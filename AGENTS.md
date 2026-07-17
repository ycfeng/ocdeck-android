# Repository Agent Rules

[简体中文](AGENTS.zh-CN.md)

This English document is canonical. `AGENTS.zh-CN.md` is its complete Simplified Chinese convenience translation.

Any change to `AGENTS.md` or `AGENTS.zh-CN.md`, including a change explicitly requested by the user for only one file, must update both files in the same change and preserve semantic parity.

## Scope and Precedence

- This repository develops OC Deck, an independently community-maintained native Android client for OpenCode Server. OC Deck is not built by, endorsed by, sponsored by, or affiliated with the OpenCode project or Anomaly.
- Reproduce OpenCode's core workflow without mechanically copying desktop Web dialogs, layouts, or interactions. Prioritize mobile usability, stable connectivity, real-time state consistency, and protection of sensitive data.
- When OpenCode source code is needed as a reference, first read `opencode.source.dir` from `local.properties` and use that local source tree when the configured path exists. Consider online search only when the property is not configured or the configured path does not exist.
- Before using Playwright to inspect or test OpenCode Web, read `opencode.web.url` and `opencode.test.project.dir` from `local.properties`. Use the configured local Web root, and restrict interactions that can modify project files, sessions, or project state to the configured dedicated test project. If either property is not configured or the test project path does not exist, do not perform state-changing Playwright interactions.
- Before changing implementation, read the canonical [mobile interaction design](doc/architecture/mobile-interaction.md) and [project framework](doc/architecture/project-framework.md); complete Chinese translations are linked from the [documentation index](doc/README.md), which also lists narrower guidance.
- If implementation, tests, and documentation disagree, keep the change simple and correct, verify the intended behavior, and update every affected English/Chinese document pair in the same change. Do not knowingly leave a contradiction.

## Language and Communication

- Reply in the language of the user's current request. If the request is mixed or ambiguous, continue in the language already established in the conversation. An explicit language request always wins.
- When creating a subagent, both its title and prompt must use the language of the user's current request.
- Compaction summaries, continuation prompts, and handoff notes must record and preserve the selected conversation language so resumed work uses the same language.
- Keep code identifiers, commands, file paths, protocol names, and Git commit messages in English unless a user-facing localized value requires otherwise.
- Final implementation reports should identify the affected modules, why those boundaries were changed, any cross-service or cross-configuration impact, and the most relevant verification performed.

## Sources of Truth

- Application version: `VERSION_NAME` and `VERSION_CODE` in `gradle.properties`.
- Android and Kotlin dependency versions: `gradle/libs.versions.toml` and the active Gradle build files.
- Bridge, Go, x/mobile, Android API, NDK, and frp versions: `frpc-stcp-visitor-go/bridge-versions.properties`.
- Architecture, interaction, API completion, and current capability status: `doc/architecture/project-framework.md` and `doc/architecture/mobile-interaction.md`.
- Test scope and commands: `doc/development/testing.md` and `.github/workflows/ci.yml`.
- Release behavior: `.github/workflows/release.yml` and `doc/release/github-actions.md`.
- Do not copy mutable version tables, endpoint matrices, or feature-status inventories into this file. Read them from their canonical sources and update those sources when behavior changes.
- Prefer stable dependencies. Do not switch to alpha/canary Android dependencies unless the user explicitly requests preview software. Never use floating GoMobile or frp versions such as `gomobile@latest`.

## Working and Git Rules

- Create a Git commit only when the user explicitly asks for `git commit`.
- Commit messages must be English and start with `feat:`, `fix:`, or `refactor:`. Use `feat:` for new or improved capability, `fix:` for defects, and `refactor:` only when external behavior is unchanged.
- Stage and commit only changes related to the current task. Do not include unrelated user work unless explicitly requested.
- Do not commit files matched by `.gitignore`, and do not use `git add -f` or `git add --force`, unless the user explicitly names the ignored file. A request to commit "all changes" is not sufficient authorization.
- Never revert, overwrite, or clean unrelated worktree changes. If concurrent changes directly conflict with the task, stop and ask how to proceed.
- Never commit, log, paste, or document real credentials, signing material, private project content, prompts, or raw sensitive responses.

## Architecture Boundaries

- Keep business implementation in the single `:app` module and organize responsibilities by package. Do not split feature/data/domain business Gradle modules prematurely.
- `:frpc-stcp-visitor` is the approved Android library boundary for stable Kotlin interfaces to the GoMobile AAR. `frpc-stcp-visitor-go` is its pinned Go build project. Neither location may contain app feature/data/domain business code, and this boundary is not precedent for business-module migration.
- Use manual dependency injection through `AppContainer`, constructor-injected ViewModels, and centralized factories. Do not create ad hoc `OkHttpClient`, Retrofit, Repository, or application-scoped singleton instances in feature code.
- Use DataStore for lightweight settings, Android Keystore for sensitive credentials, and in-memory Stores for runtime state.
- Do not introduce Room, Hilt, KSP, kapt, or business-module splitting without explicit user approval based on a demonstrated need such as offline cache/search, fast cold restoration, or an unmanageable dependency graph.
- Package responsibilities remain: `app/` for application assembly; `core/network/` for HTTP/SSE/auth/error handling; `core/security/` for Keystore and redaction; `core/store/` for runtime state and reducers; `core/navigation/` for centralized routes; `core/util/` for shared normalization and utilities; `data/` for DTOs and Repository implementations; `domain/` for models/interfaces/use cases; `feature/` for screens and ViewModels; and `ui/` for shared components and theme.
- Prefer Navigation Compose typed routes for new routes and migrate existing string routes incrementally rather than expanding the transitional string scheme.

## Mobile UI and Localization

- Use Single Activity, Jetpack Compose, Material 3, and Navigation Compose. Preserve established project patterns instead of introducing a parallel UI architecture.
- Do not copy desktop two-column dialogs or unconstrained popovers onto phones. Prefer single-column full-screen settings/forms, screen-constrained anchored popups for short content, inline panels for composer suggestions, and `ModalBottomSheet` for longer searchable or complex content.
- Use `ModalNavigationDrawer` for the project/session shell. Popups, sheets, docks, and inline panels must be focusable, dismissible with system back, size constrained, and clear of the IME and bottom composer.
- Keep the bottom composer fixed with correct IME insets. Every clickable icon and chip action must have a real touch target of at least 48 dp even when its visual icon is smaller.
- Tabs, radio choices, checkboxes, and switches must expose selected/checked semantics through the matching role. Expandable controls need localized expanded/collapsed state descriptions.
- Unread, error, permission, connection, and other important states must not rely on color alone. Reorderable server cards must provide TalkBack move-up/move-down custom actions in addition to drag gestures.
- In both themes, body and supporting small text target at least 4.5:1 contrast, while required non-text state indicators and control boundaries target at least 3:1. Real text-field boundaries use `ControlBorder`; semantic Diff, Markdown, syntax, and chart colors remain covered by JVM contrast tests.
- Prefer natural text measurement and `heightIn` over fixed text heights. Dialogs, server status, settings sheets, suggestion panels, and composer attachments must remain screen constrained and scrollable so key actions stay reachable on small screens, at 200% font scale, and with the IME visible.
- Any visual change involving color, background, border, shadow, or state styling must work in both light and dark themes. Do not hard-code a one-theme color.
- Do not hard-code new or changed user-visible text in Kotlin. Put Simplified Chinese in `app/src/main/res/values/strings.xml` and English in `app/src/main/res/values-en/strings.xml`, adding every key to both files.
- ViewModel or Store fallback text that reaches UI must use `UiText.Resource`. Non-composable helpers should return semantic data or `UiText`, not call `stringResource` directly. Server data, user input, paths, filenames, session titles, provider/model names, and command output remain unchanged.
- After UI text changes, run `rg '[\p{Han}]' app/src/main/java/io/github/ycfeng/ocdeck --glob '*.kt'` and review both Chinese and English hard-coded UI text; comments, technical identifiers, and server/user data are exceptions.

## Paths, API, and Project State

- Normalize every OpenCode `directory` before it reaches a Repository or network boundary: replace `\` with `/`, remove a trailing `/` except for root, compare Windows drive paths case-insensitively, deduplicate recent projects by normalized worktree path, and use normalized paths for internal keys.
- Use `ProjectFilePathNormalizer` separately for project-relative file paths. Reject NUL, absolute paths, and every `..`; preserve the documented Windows/POSIX separator semantics and verify server directory nodes are direct children of the requested directory.
- Never bypass path normalization for speed or convenience.
- Use Retrofit + OkHttp + kotlinx.serialization for normal REST work, and keep JSON parsing tolerant of unknown fields.
- Every `OpenCodeApi` Retrofit method must declare an explicit inbound response policy. Ordinary successful JSON/object/list/Boolean/`JsonElement` responses and `/file/content` have both a 16 MiB encoded response-body limit before `Content-Encoding` decoding and a 16 MiB decoded-entity limit; successful `Response<Unit>` bodies are discarded.
- The Retrofit inbound interceptor fails closed when a policy is missing and tags bounded requests for the encoded-body network interceptor. Both limits use `Content-Length` only for early rejection and enforce unknown or understated lengths at `max + 1`. Every non-2xx body is closed and replaced with an empty body before Retrofit can cache it while preserving the status code. Requests without a Retrofit `Invocation`, including the direct session-message transport, are outside the Retrofit interceptor and must attach their own encoded-body policy.
- Project entry remains: normalize `directory`, fetch project REST snapshots in parallel, write them to the in-memory Store, open project SSE, reduce incremental events into the Store, and perform health/snapshot reconciliation after foreground restoration.
- The complete endpoint matrix and completion status live in `doc/architecture/mobile-interaction.md`. When an endpoint changes, update tolerant DTO handling, directory/workspace parameter tests, and the paired interaction documents.

## SSE and Connection Consistency

- Support both global and project SSE. Production SSE must use OkHttp `Call` plus a custom streaming reader over `ResponseBody.source()`, not an unbounded parser that first materializes event `data` as a complete `String`.
- Request SSE with `Accept-Encoding: identity` and reject any non-identity content encoding before opening or reading the body. Limit each physical/logical SSE line and accumulated event data to 32 MiB of UTF-8 identity response-body bytes. Enforce line limits before conversion to `String` and event limits before complete concatenation. On overflow, close the body, cancel the Call, and report a typed failure without payload content.
- Accept only HTTP 200 with media type `text/event-stream`, allowing charset parameters. Do not read response bodies for non-200 or invalid content types. Treat 204 and non-retryable 4xx as `Failed`; keep 408/409/425/429 and 5xx retryable.
- Dispatch an event only after a complete empty line. At EOF, discard pending lines/events not terminated by that delimiter. Drop an individual event with invalid JSON or field types without closing the connection; JVM `Error` must still propagate.
- Explicit cancellation must not turn its resulting I/O failure into a reconnect callback. Expose `Connecting`, `Open`, `Retrying`, `Failed`, and `Closed`, with bounded exponential backoff.
- Never clear existing UI data on disconnect. Reconcile with a REST snapshot after reconnect. Snapshot failure must not block EventSource reconnection, and one recovery should launch at most one mergeable reconciliation plus a bounded follow-up when marked dirty.
- Opening, closing, retrying, callbacks, and reconciliation must validate owner lease, generation, source, and transport identity. Late work after close must not publish `Open` or recreate a closed connection.
- When a project stream is `Open` on the same transport identity, it is authoritative for that project; use global project events only as fallback while the project stream is unavailable. Buffer reconciliation events with count and estimated-byte limits, never without bounds.
- Redact SSE and snapshot errors before storing or displaying them. An application-level connection coordinator owns foreground health/readiness checks and any required snapshot refresh.
- In STCP mode, wait for visitor readiness and application health before starting REST or SSE. Reconcile after control-epoch changes, and reject results from old generations or epochs.
- When workspace support is enabled, Store, SSE, ViewModel, operation-gate, and reconciliation keys must include both normalized directory and workspace.

## Composer and Session Operations

- A new session has no backend session until the first send. Create it with `POST /session`, then use `POST /session/{sessionID}/prompt_async` for an ordinary prompt.
- Route to `POST /session/{sessionID}/command` only when the slash command matches loaded `/command` data. Unknown `/xxx` input remains an ordinary prompt. Shell mode, when implemented, uses `POST /session/{sessionID}/shell`; Stop uses `POST /session/{sessionID}/abort`.
- Disable send when text, attachments, and context are all empty. If the current session is working and there is no new input, turn submit into Stop. Validate model and agent before sending.
- Treat the current project's Store `PromptCapabilities.revision` and a frozen capability snapshot as authoritative. Reject stale UI revisions, and make the revision check and optimistic user-message insertion atomic.
- On failure, remove only the same message while it is still optimistic. If SSE already confirmed it, preserve the message and report an uncertain result to avoid duplicate retries.
- `send`, `abort`, `revert`, and `unrevert` must share a fail-fast single-flight gate keyed by server/directory/workspace/session, not rely only on disabled buttons. After a new session obtains its real ID, add that key to the existing lease before publishing Store or navigation state.
- Agent picker shows only composer-supported `build` and `plan`. Model picker groups by provider and supports search and current selection; Connect Provider and Manage Models must navigate to their real settings routes. Variant picker appears only for model-provided variants, adds a localized Default option, resets unsupported selections, and uses Reasoning wording. `/` suggestions and `@` agent mentions use separate data sources; mentions do not reuse file search.

## Security and Credential Transactions

- Replace sensitive information with exactly `<redacted>`. Sensitive data includes API keys, tokens, passwords, authorization/cookie values, provider auth and custom headers, SSH passwords/private keys/passphrases/host fingerprints, frp/STCP secrets, environment values, signing material, and credential-bearing config values.
- Use one `Redactor`. Prefer structured redaction for known JSON, headers, queries, URLs, and configuration objects; use regex only as a fallback for unknown free text. Bare authentication-scheme values and HTTP(S) URL userinfo/fragments must also be redacted.
- Enable network logging only in debug builds and only through a redacting interceptor. Error UI, crash reports, tests, snapshots, debug panels, and documentation must not expose raw headers, credentials, full sensitive bodies, or real secrets. Provider settings display only connection or masked state, never a key in plaintext.
- Repository, network, and Store layers keep typed semantic failure reasons rather than deriving user copy from exception `toString()` or `Throwable.message`. UI maps those reasons to localized resources with an operation-specific fallback; cancellation and JVM `Error` must propagate.
- Accept a server base URL only when it is HTTP(S), has a valid host, and has no userinfo, query, or fragment. Use the same validation for save, connection, and display; do not echo an invalid legacy value.
- Before saving a Direct server that combines non-loopback cleartext HTTP with active OpenCode Basic authentication, show an explicit advisory confirmation. Active authentication requires a nonblank username and a new or retained password. Classify loopback syntactically without DNS; do not apply this warning to SSH/STCP. Save Anyway must preserve normal saving and later use, while cancellation must perform no repository write.
- Validate fixed SSH host fingerprints with `HostKeyRepository` or an equivalent mechanism before user authentication. Never connect with strict checking disabled and verify only afterward.
- Creating/updating server credentials or saving a TOFU fingerprint must first write each new secret to a purpose-specific candidate alias with a random UUID. Check cancellation after candidates are built. Configuration write, outcome confirmation, connection-config epoch update, and SSH/STCP runtime invalidation must run in a `NonCancellable` commit section.
- If configuration write throws, reread the target: continue as success if it persisted; clean candidates and rethrow only when definitely not committed; if the outcome cannot be confirmed, retain candidates and throw a typed unknown-outcome error without secrets.
- After a successful commit, reread all server references outside the configuration lock and delete old aliases best-effort. Cleanup failure must not roll back configuration. `SecureCredentialStore` writes/deletes must use failure-reporting SharedPreferences `commit()` on an IO dispatcher, catch only expected `Exception`, and propagate cancellation and JVM `Error`. Before deleting or rotating an alias, verify that no other server still references it.
- A changed SSH host or port must not inherit the previous endpoint's fingerprint alias. `AcceptNew` clears the pin for new TOFU; `Fingerprint` requires a new pin.
- Sensitive or potentially large value objects must override `toString()` with a structural summary that omits credentials, URLs/endpoints, aliases, paths, prompts, Base64, SSE payloads, and tool output; sensitive fields use exactly `<redacted>`. These summaries must not change serialization, `copy`, equality, or hashing behavior.

## Files, Attachments, and Bounded Input

- Browse server project files through OpenCode `/file`, `/file/content`, and `/find/file`; do not substitute Android SAF for the server filesystem. Android local attachments may use the system picker, and UI/data models must distinguish local-device files from server project files.
- Apply streaming hard limits before fully allocating URI, `ResponseBody`, Base64, native bridge, image, or private-key data. Do not use unbounded `readBytes()`, `readText()`, whole-body buffering, or decode-then-check patterns.
- `GET /session/{sessionID}/message` must bypass Retrofit converters and error-body caching. Use an OkHttp `Call.Factory` from `ServerConnection` that shares REST authentication, timeout, redirect, and redaction configuration. For non-2xx, do not read the body; close it and throw a typed body-free HTTP error.
- Decode a 2xx session-message body on the OkHttp callback thread with a counting/limiting `InputStream` and the shared tolerant `Json.decodeFromStream`. Attach a 64 MiB encoded response-body policy before executing the Call, then enforce a separate 64 MiB decoded-entity limit while decoding and verifying EOF. `Content-Length` is only an early rejection; unknown or understated lengths fail at `max + 1`. Coroutine cancellation must cancel the Call and close the active body without replacing `CancellationException` with an I/O error.
- The 16 MiB Retrofit/file and 64 MiB session-message limits apply separately to encoded response-body octets after HTTP transfer framing and to decoded entity bytes. The 32 MiB SSE limits apply to the identity response-body representation. These are not heap-usage guarantees: Retrofit converters may still materialize a complete `String`, DTO graph, or JSON tree within the limit, and concurrent project snapshots can still create a high memory peak. `/file/content` retains its reader-level decoded defense.
- Attachments must limit per-file size, file count, and total raw bytes. The sender boundary must revalidate required metadata, data-URL headers, Base64 characters/whitespace/padding, encoded length, and declared raw size.
- SSH private-key files are limited to 256 KiB, read with a bounded buffer on an IO dispatcher, and revalidated by UTF-8 byte count at save and JSch boundaries.

## Testing and Verification

- Add or update focused tests for changed behavior. The detailed test inventory belongs in `doc/development/testing.md`; do not duplicate it here.
- Core paths require tests covering the relevant normalization, redaction, DTO/error handling, prompt state/optimistic rollback/single-flight, Store reducers, SSE protocol/lifecycle/bounds, credential transactions, SSH host-key timing, STCP readiness/epochs, and bounded external input behavior.
- For ordinary Android changes, run from the repository root:

```bash
./gradlew :app:testDebugUnitTest :frpc-stcp-visitor:testDebugUnitTest :app:assembleDebug
```

On Windows use `gradlew.bat`.

- Changes to `frpc-stcp-visitor-go/`, `:frpc-stcp-visitor`, the frp patch, bridge API/dependency, or bridge version must also run CI-equivalent gates: `go run ./cmd/preparefrp`; wrapper and patched frp client `go test -race`; `build-aar.sh` or `build-aar.ps1`; `:frpc-stcp-visitor:checkGoMobileBridgeAar -PrequireGoMobileBridge=true`; both Android unit-test tasks; and `:app:assembleDebug`. A Kotlin-only bridge API or failure-handling change can leave generated AAR bytes and `BRIDGE_VERSION` unchanged, but it does not waive this full gate; increment `BRIDGE_VERSION` whenever native/AAR bytes change.
- Bridge gates must continue to verify checksum, Java API signature, bridge/frp provenance, expected ABIs, ELF machine, 16 KiB `PT_LOAD` alignment, stripped status, and reproducibility from identical inputs.
- Documentation/community-only changes should run `python .github/scripts/audit-community.py`; add broader tests only when the changed files affect code, build, release, or third-party validation.

## Documentation, Release, and Third-Party Rules

- Public documentation uses English canonical files with complete `.zh-CN.md` convenience translations. Paired files must link to each other and be updated together whenever facts, commands, status, security constraints, or links change.
- Modifying either `AGENTS.md` or `AGENTS.zh-CN.md` always requires modifying both in the same change, even if a user asks for only one language version. Preserve equivalent requirements and section ordering.
- Update both project-framework documents for substantive architecture, version, interface, or page-flow changes. Update both mobile-interaction documents for interaction, mobile adaptation, endpoint behavior, or newly observed OpenCode Web behavior.
- When community entry points, document paths, release notes, compatibility, or deprecation policy change, update all affected pairs and run `.github/scripts/audit-community.py`.
- New security rules or sensitive fields require synchronized updates to both AGENTS files, relevant paired documents, and implementation tests.
- `gradle.properties` is the only source for application release version. Stable tags are `vMAJOR.MINOR.PATCH`, must equal `VERSION_NAME`, and require increasing `VERSION_CODE`. The default branch is `main`.
- GitHub Release keeps only the three supported ABI APKs and `SHA256SUMS`; do not add an AAB or universal APK unless release policy explicitly changes. GitHub and future Google Play packages must use the same app-signing certificate. Never commit, cache, or upload JKS/password/Base64 keystore material; release must verify the pinned certificate SHA-256 fingerprint.
- If GoMobile bridge bytes change, increment `BRIDGE_VERSION`; never publish different bytes under the same Maven coordinate. Before release, perform device validation for native loading, each target ABI, 16 KiB page size, and a real STCP end-to-end connection in addition to static gates.
- Adding or upgrading dependencies, sounds, Gradle wrapper, frp upstream/patches, or distributed assets requires synchronized updates to `THIRD_PARTY_NOTICES.txt`, `third_party/components.toml`, relevant `third_party/sources/*`, and license texts. Recompute hashes from actual local bytes.
- Do not reintroduce removed third-party provider marks as distributed assets. Review source, license, redistribution, and trademark implications before adding branded graphics; prefer text or generic icons by default.
