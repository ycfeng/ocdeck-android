# OC Deck: Native Android Client Interaction Design for the OpenCode Web UI

[简体中文](mobile-interaction.zh-CN.md)

This document and the Chinese version are maintained in parallel. If the documents conflict, the current implementation, automated tests, and the [repository agent rules](../../AGENTS.md) take precedence.

## 1. Document Scope

This document is based on Playwright exploration of `http://127.0.0.1:4096/` with a `390 x 844` viewport approximating an iPhone 12 Pro in portrait orientation. Observed data has been rewritten as generic fixtures: the test project uses `X:/workspace/sample-project`, and POSIX examples use `/workspace/sample-project`.

The goal is to provide implementable page, interaction, state, API, and mobile-adaptation designs for reproducing the core OpenCode Web UI workflow in OC Deck. OC Deck is an independent community client and is not an official product of or affiliated with OpenCode or Anomaly. Application version `0.1.1` uses `io.github.ycfeng.ocdeck`, supports Android API 26 and later, and requires users to provide an accessible OpenCode Server. This document contains no real provider API keys, tokens, passwords, or local credentials. Sensitive fields in configuration APIs must be replaced with `<redacted>` in logs, documentation, crash reports, and debugging UI.

The exploration did not perform side-effecting actions such as sending prompts, archiving or deleting sessions, closing projects, disconnecting providers, saving project edits, or adding servers. Send and management APIs were inferred from the static frontend JavaScript client and network records.

## 2. Observation Environment

| Item | Value |
| --- | --- |
| Web URL | `http://127.0.0.1:4096/` |
| OpenCode Web title | `OpenCode` |
| Health-check version | `/global/health` returns fixture version `<fixture-server-version>` |
| Desktop settings version | `<fixture-desktop-version>` |
| Test project path | `X:/workspace/sample-project` |
| Test project VCS | `git` |
| Test project branch | `main`, displayed as `Main branch (main)` |
| Test project ID | `project_fixture_001` |
| Mobile viewport | `390 x 844` |

## 3. Information Architecture Overview

The native Android client should not mechanically reproduce desktop Web dialog layouts. It should reorganize them for mobile devices as follows.

| Module | Web behavior | Android recommendation |
| --- | --- | --- |
| Server/project selection | Logo, server, recent projects, and Open Project button | Select a server, then enter a project picker with recent projects and an open-project entry |
| Project session shell | 40 px header, main content, bottom composer, and left drawer | Single Activity with multiple screens, `TopAppBar`, `ModalNavigationDrawer`, and a fixed bottom composer |
| Session list | Project rail, session list, and Load More inside the mobile drawer | `ModalNavigationDrawer` or a dedicated Projects and Sessions screen; archive through swipe or an overflow menu |
| Session details | Session/Changes tabs, message stream, and bottom input | `SessionDetailScreen` with `TabRow`, `LazyColumn`, and `BottomComposer` |
| Change review | Empty state or diff in a tab; desktop review area is nearly invisible on mobile | Currently hosted by the Changes tab in session details; the standalone Review route remains a placeholder and must not be placed off-screen |
| Status/server | Top-right popover and server-management dialog | Constrained anchored popup for short status; full-screen page for server management |
| Settings | 600 px desktop two-column dialog that becomes extremely narrow on mobile | Single-column `SettingsScreen`; do not use the desktop two-column layout |
| Provider/models | Provider cards and model switches in settings, model selector in composer | Full-screen provider/model settings; constrained popup or bottom sheet according to content |
| Composer | Text, attachments, agent, model, variant, slash commands, and mentions | Custom Compose input with chips, attachments, command panel, and state restoration |
| Help | Opens Discord in a new tab | Android Custom Tabs or browser intent |

## 4. Routing and Path Normalization

Web routes encode the project path into the URL, for example:

`/<base64(normalized-directory)>/session`

Session detail route:

`/<base64(normalized-directory)>/session/ses_fixture_001`

An important observation is that the Web UI showed both `X:\workspace\sample-project` and `X:/workspace/sample-project`. This produced two `sample-project` icons in the rail with different route Base64 values.

Android must normalize paths:

| Input | Recommended normalization |
| --- | --- |
| `X:\workspace\sample-project` | Convert to `X:/workspace/sample-project` |
| `X:/workspace/sample-project/` | Remove the trailing `/` |
| Case-insensitive file systems | Compare Windows drive-letter paths case-insensitively |
| Recent-project deduplication | Use the normalized worktree path as the unique key |

The native client does not need to reproduce Web route encoding, but network requests must carry the `directory` query. Maintain a global `ProjectRef(normalizedDirectory, projectId, displayName, vcs, icon)`.

## 5. Project Selection and Recent Projects

### 5.1 Page Structure

At mobile width, the Web welcome page has a 40 px header with a Toggle Menu button. The main area contains the OpenCode logo, server button `127.0.0.1:4096`, recent-project list, and Open Project button. The native implementation does not retain a separate welcome route. When server data has loaded and the list is empty, the server list page displays the OpenCode logo and client explanation, explicitly states that the app does not create or run OpenCode Server, and asks the user to start an accessible service before adding a connection. Selecting an existing server enters `ProjectPickerScreen(serverId)` directly. After adding a server successfully, return to `ServerListScreen` so opening that server remains an explicit user action.

Observed recent-project fixtures:

| Recent project |
| --- |
| `/` |
| `X:\workspace\sample-project` |
| `X:\workspace\sample-library` |
| `/workspace/sample-service` |
| `/workspace/sample-cli` |

Selecting `X:\workspace\sample-project` enters the project session page.

### 5.2 Open Project Dialog

Web dialog fields:

| Element | Behavior |
| --- | --- |
| Title `Open project` | Close button at the upper right |
| Search field `Search folders` | Filters by path or keyword |
| Recent projects | Splits the target project into `X:/workspace/` + `sample-project` + `/` |
| `Clear filters` | Appears after input and clears the search |
| Directory list | Shows folders such as `doc/`, `.git/`, `src/`, `tests/`, and `build/` |

Entering `X:/workspace/sample-project` shows the matching recent project and directories under that path. Selecting the recent project closes the dialog and routes to its most recent session.

Android recommendation: use a full-screen `ProjectPickerScreen` or `ModalBottomSheet`. Folder browsing must not depend on Android SAF because OpenCode Server reads its own server-side file system. Use `/find/file` and `/file` to display server directories.

Current native implementation: `ProjectPickerScreen` keeps the `Open project` title. The upper-left Server action enters server management, and the upper-right Settings action enters settings. The top bar has neither a Back nor Open button; the open action remains in the page body, and there is no extra Back button at the bottom. Selecting a candidate directory completes the normalized path in the input, appends `/`, and continues querying candidates inside that directory while moving the cursor to the end; it does not enter the project. The user must press the body Open Project button. Pressing the body of a recent-project card opens it, while the trailing delete icon removes only the local recent-project record. Before deletion, show an OpenCode-style confirmation explaining that server project files will not be deleted.

## 6. Project Home

After entering a project without selecting a session, the main area shows:

| Area | Copy/state |
| --- | --- |
| Title | `Build anything` |
| Path | `X:/workspace/sample-project` |
| Branch | `Main branch (main)` |
| Time | Relative text such as `Last modified <fixture-relative-time>` |
| Bottom composer | Fixed to the bottom; Send is disabled when input is empty |

In the current Android project-home composer preview, the Agent, Model, and Variant controls open their pickers in place and never navigate to a new session. Agent choices are limited to the loaded Composer-supported Build and Plan agents. Model and Variant use the same constrained pickers as the session composer, including search, provider grouping, current selection, Default variant handling, and real Connect Provider/Manage Models routes. Clicking the input area or attachment action enters the `"new"` session page and carries the current lightweight agent/model/variant selection; the session ViewModel revalidates it against the loaded capability snapshot and falls back through the normal model-selection rules when stale or unsupported.

Selecting New Session in the drawer only changes the URL to the project `/session` route. It does not create a backend session ID or issue a POST. The first message sends `POST /session` to create the session.

Current Android implementation: a new session reuses `SessionDetailScreen` and represents the no-backend-session state internally as `"new"`; there is no separate `NewSessionScreen`. The real session is created only on first send, after which the fixture state is replaced with the real session ID.

## 7. Top Bar and Navigation Drawer

### 7.1 Top Bar

The mobile top bar is approximately 40 px high.

| Element | Behavior |
| --- | --- |
| `Toggle menu` | Opens or closes the project and session drawer |
| `Status` | Opens the server-status popover |
| `Toggle review` | Toggles the review/change area; the mobile effect is limited |

### 7.2 Project and Session Drawer

Pressing Toggle Menu slides navigation `Projects and sessions` from `x=-390` to `x=0`, with approximate width 390 and height 804.

Drawer structure:

| Area | Content |
| --- | --- |
| Left rail | Same-server recent-project buttons, Open Project, and Settings at the bottom; Android does not show the Web help question-mark button |
| Project header | `sample-project`, path, and project overflow actions |
| Actions | `New session` |
| Session list | Session link plus trailing `Archive` button per row |
| Pagination | `Load more` |

The Android rail observes the current server's persisted recent projects, which contain at most 20 MRU entries, and merges in the active project when it is absent. Every directory is normalized and deduplicated with the platform-aware path comparison key. Project navigation never waits for this auxiliary DataStore write: an application-scope ordered recorder updates the MRU best-effort after navigation succeeds, and a local persistence failure cannot block an otherwise accessible project. Entering a project, returning to an existing project-home destination, and opening a session directly all refresh the same MRU order. The active project's live metadata replaces a stale matching recent entry without creating a duplicate.

Project buttons live in a bounded `LazyColumn` whose visible height is at most 75% of the safe drawer content height. Overflow scrolls vertically, while Open Project and Settings remain fixed and reachable. Each 48 dp project target uses its first Unicode character cluster, exposes a localized full project-name-and-path label, participates in one `selectableGroup` with `Role.Tab`, and has exactly one selected item. Only the active project shows the currently available notification state; the app does not fabricate unread, permission, or working indicators for inactive projects that are not loaded.

Selecting a rail project closes the drawer first. Selecting the project already visible on its project home does not add another destination; selecting it from a session detail returns to that project home. Selecting another project pops back to an existing matching project-home destination when possible, otherwise it opens a new `ProjectShellRoute`. The rail enters the project home rather than restoring the Web client's last session because recent-project persistence does not contain a session ID.

The project home and drawer share one Store-backed session window per normalized server/directory/workspace key. They initially show 20 root sessions and request `/session?roots=true&limit=70`, keeping 50 raw entries of headroom for archived filtering. Load More raises the shared visible target by 20; it reveals already loaded roots immediately or refetches the ordered prefix with `limit = target + 50`. A raw response shorter than the requested limit marks the end, while failure keeps existing sessions and exposes an inline retry. Snapshot reconciliation preserves the current requested window instead of shrinking back to a fixed limit. `GET /session/{sessionID}` supplements a route that targets a session outside the current window and follows at most 16 parent links with cycle detection.

Visible fixture sessions:

| Title |
| --- |
| `Example session 1` |
| `Example session 2` |
| `Example session 3` |
| `Example session 4` |
| `Example session 5` |

Mobile issue: after closing, the drawer remains in the DOM at `x=-390` and can occasionally intercept pointer events over the bottom parameter strip. Android must not reproduce this off-screen hit-testing behavior. Use the standard `ModalNavigationDrawer`, which no longer participates in touch hit testing after closing.

### 7.3 Project File Panel

Supplementary desktop exploration used a `1440 x 1000` viewport and `/workspace/sample-project`. The Web Review and Files area is mounted on the right at widths of at least 768 px. The All Files tree starts at about 200 px and can be resized from 200 to 480 px. Directories lazily call `/file` for direct children, and selecting a file adds a file tab on the left and calls `/file/content`. At 800 px this two-column layout already compresses file content heavily; at 767 px it is not mounted at all, so it should not be transferred directly to phones.

Current Android implementation:

- Both `ProjectShellScreen` and `SessionDetailScreen` expose the project-file entry in the top bar. The project shell owns the state so navigation within a project does not create duplicate file trees.
- Phones use a right-side overlay panel that does not compress the session or bottom composer. It fills small screens and is capped at 420 dp on larger screens. Tapping the outside scrim, pressing system Back, or using Close dismisses it. Back returns from preview to the tree before closing. Closing cancels active requests and releases current text/Base64 content while retaining the successful project-scoped directory cache.
- The panel has explicit Browse and Pick modes. Tree and content remain single-page states rather than desktop multi-file tabs. Directories expand lazily and are cached, with directories sorted before files. Search data is separate from tree data; non-empty searches call `/find/file?type=file`.
- Composer's Choose from Project action opens Pick mode with the draft's current files preselected. A file row toggles checkbox selection, a separate 48 dp action opens preview, and a fixed footer confirms up to 10 whole files. Cancel or Back does not change the draft. The temporary selection request is bound to the originating server, normalized directory, workspace, session route, and request; route changes close it and discard late delivery, while the browser cache remains project-scoped.
- Text files use Prism4j syntax highlighting, line numbers, a monospace font, and a vertically virtualized list. Network responses are capped at 16 MiB before parsing. Text preview is additionally capped at 500,000 characters, 20,000 lines, and 20,000 characters per line; exceeding a limit shows an explicit state rather than blocking or exhausting memory.
- `image/*` binary content may be decoded from Base64 for preview. Check dimensions before decoding and sample to at most approximately 4 MP. Unknown binary formats, oversized images, and decode failures each show a distinct state. The first version does not support editing, saving, audio, PDF, line comments, or adding a line/range selection to a prompt.
- File-relative-path normalization is separate from project-root normalization: reject NUL, absolute paths, and any `..`; treat backslashes as separators for Windows projects while preserving legal backslashes in POSIX filenames. The Repository also validates that each `/file` node is a direct child of the requested directory. Build a prompt `file://` URL only from the normalized project root plus a validated relative path, percent-encode each UTF-8 path segment, and never trust or forward the server DTO's absolute path.

## 8. Project Overflow Menu and Project Editing

Project overflow menu items:

| Menu item | Observation |
| --- | --- |
| `Edit` | Opens the edit-project dialog |
| `Enable workspaces` | Not executed; expected to relate to worktree/workspace support |
| `Clear notifications` | Disabled when there are no unread notifications; when enabled, marks project notifications as read |
| `Close` | On Android, exits the current project view after confirmation without deleting the server project or recent-project record |

Edit-project dialog fields:

| Field | Description |
| --- | --- |
| Name | Text field whose value/placeholder is `sample-project` |
| Icon | Click or drag an image; 128x128 px is recommended |
| Color | `pink`, `mint`, `orange`, `purple`, `cyan`, `lime`; `lime` is currently pressed |
| Workspace startup script | Multiline field with placeholder such as `bun install`; runs after a new workspace/worktree is created |
| Actions | `Cancel`, `Save` |

Save API: `PATCH /project/{projectID}`. The body contains `name`, `icon`, and `commands`; the query contains `directory` and `workspace`.

Current Android implementation: the project overflow menu in the drawer reproduces the Web menu size and visual style. Edit provides only a project-name dialog and sends only `{ name }`. Clear Notifications is available when unread notifications exist and marks the project as read in the in-memory notification Store. Project information is loaded into the in-memory Store through `GET /project/current`. Enable Workspaces is not shown. Full icon, color, and startup-script editing is deferred to a later `ProjectEditRoute`.

Android recommendation: use `EditProjectScreen` or `ModalBottomSheet`, render colors as horizontal chips, and use Android Photo Picker or a file selector before forwarding icon data to the backend capability. Show dirty state before save and warn about unsaved changes when navigating back.

## 9. Session Detail Page

### 9.1 Top Tabs and Title

Session detail URL:

`/.../session/ses_fixture_001`

Page structure:

| Area | Content |
| --- | --- |
| TabList | `Session` selected, plus `Changes` |
| Title | `Example session` |
| Title actions | `View context usage`, `More options` |
| Message area | Scrollable content |
| Composer | Fixed at the bottom |

### 9.2 Message Stream

Fixture messages:

| Role | Rendering |
| --- | --- |
| User | Right-aligned sequence bubble `1`; metadata `Build · model-standard · <fixture-time>`; actions `Reset to here` and `Copy message` |
| Assistant | Text `This is a fixture assistant response.`; action `Copy response`; metadata `Build · model-standard · <fixture-duration>` |

Assistant-message duration must follow the Web definition. For the same user turn, measure from the user message's `time.created` to the maximum `time.completed` among all assistant messages whose parent is that user message, and round to the nearest second. Only when no user turn can be associated may the client fall back to one assistant message's `completed - created`.

Android recommendation: render messages with `LazyColumn(reverseLayout = false)`. Put message actions in long press, overflow, or an action area below the content, and copy through Android Clipboard. Enable text selection per message: Compose selection for user text, selectable `TextView` blocks for assistant Markdown paragraphs, and Compose selection containers for code blocks. Exclude role, model, status, and time metadata from selection to avoid copy noise. Error text uses its own selection container; when response content is empty, Copy Response copies the error text. Keep whole-message Copy Message/Copy Response as a fallback. `Reset to here` on a user message maps to `POST /session/{sessionID}/revert`. Render agent/assistant responses as Markdown supporting at least paragraphs, lists, links, inline code, and fenced code blocks. Match Web inline code with cyan monospace text on a transparent background rather than a gray chip. Fenced blocks use a light Web-like background, 1 dp border, monospace font, horizontal scrolling, and syntax highlighting. User messages remain plain text so user input is not interpreted as Markdown.

Session history from `GET /session/{sessionID}/message` bypasses Retrofit converters and Retrofit's error-body delivery path. `ServerConnection` directly creates the request with the same OkHttp client and therefore shares authentication, timeout, redirect, redaction, and encoded-body limiting with normal REST calls. For non-2xx responses, do not read the body; close immediately and throw a typed HTTP error containing only the status code. A network interceptor limits encoded response-body octets after HTTP transfer framing and before `Content-Encoding` decoding to 64 MiB. For 2xx responses, decode DTOs directly on the OkHttp callback thread through the application's shared lenient `Json.decodeFromStream`, using a separate 64 MiB decoded-entity counting `InputStream` and a `Content-Length` fast rejection. After decoding, continue consuming through the same limiter to verify EOF. Unknown lengths, underreported lengths, trailing data, and infinite streams fail at the applicable limit plus one byte. Coroutine cancellation immediately cancels the Call and closes a body blocked in reading; resulting I/O failures must not replace `CancellationException`. These limits are not the actual peak heap use of DTOs, strings, Base64 data, and UI mappings, and they do not claim that other large REST responses have been audited.

#### 9.2.1 Reset and Restore

Android currently implements the Web legacy revert semantics:

- The real meaning of Reset to Here is to return to the point before the target user message was sent. The target message and following assistant/tool messages disappear from the primary timeline immediately. No confirmation is shown.
- The server stores the reversible boundary in `session.revert.messageID/partID`. REST responses and `session.updated` SSE both update the same Session marker. The in-memory Store keeps full history during revert and does not delete messages early.
- Plain text, `data:` local-device attachments, and standalone project-file contexts from the target user message are restored to the Composer. Attachment-only and context-only user messages can also be reset. A project `file://` reference is restored only when it resolves inside the current normalized project root; paired comment backing files are excluded and no `file://` reference masquerades as a local attachment. Before restore, revalidate local-attachment data URL and byte budgets plus the 10-context limit and project-path containment. Any corrupt or over-limit recoverable content leaves the server revert marker unchanged and produces a clear error. The Session marker may survive refresh, but Composer drafts are client state and cannot be rebuilt from the marker alone.
- If the Session is working, use the same session mutation lease to call `POST /session/{sessionID}/abort` before revert. This prevents reinsertion races between abort and send/revert. The restored turn remains aborted and is not resumed automatically.
- Show a collapsed-by-default restore Dock above the Composer with the reverted count, first-item preview, per-item Restore Message actions, and Restore All. Restoring an item means restoring up to and including that item: if another user message follows, move the boundary to the next one; for the last item or Restore All, call unrevert.
- Sending a new prompt, command, or shell input while reverted commits a new branch. The new optimistic message must remain visible during submission. The server later clears the marker and permanently removes the old branch through `message.removed` and `message.part.removed`; the Dock then disappears.
- Do not copy the desktop hover-only 24 px action area. Reset, copy, expand, and restore actions require at least 48 dp touch targets and must support both light and dark theme tokens.

#### 9.2.2 Quoted Comment Rendering

Web code-line comments are not separate comment resources. They are stored in two adjacent parts of a user message: a `synthetic=true` text part whose top-level `metadata.opencodeComment` contains path, line/column selection, comment body, preview, and origin; and a `file://...?start=&end=` file part that gives the model the file range. History rendering prefers structured metadata and parses the fixed synthetic-text format only when metadata is missing or damaged.

Android currently renders existing comments under these rules:

- REST DTOs and project SSE preserve and map top-level `metadata.opencodeComment`; it must not be confused with `state.metadata` inside tool state.
- Stack independent comment cards vertically on the right of a user message. Show file basename, `:N` or `:start-end`, and the full comment body. Comment text is selectable and included by whole-message copy.
- A user message containing comments but no ordinary body remains visible and does not create an empty text bubble.
- Preserve message-part order for multiple comments. Do not reproduce the Web horizontal comment strip on small screens.
- Only `data:` file parts render as local-device attachment cards. Standalone project `file://` parts render as project-file context rows using a safe filename, while the paired `file://` comment part remains hidden to avoid duplicate rendering.
- `data:image/*;base64,...` attachments in historical and live user messages display an approximately 64 dp thumbnail. Tapping it opens a near-full-width fit-to-screen image dialog. Non-image attachments keep the file icon, filename, and MIME type.
- Decode attachment images on a background thread. Before decoding, limit Base64 length, source dimensions, and pixel count, and sample for the thumbnail or full-image target. Loading and decode failures safely fall back to a file icon or separate status. The first version does not load HTTP images and does not support animation playback, zoom, download, or sharing.
- Current scope covers only history and live rendering of comments already sent by Web. Android line selection, comment editing/deletion, and comment-context sending remain separate future capabilities.

### 9.3 Context Usage

Playwright observed different Web behavior by viewport:

| Viewport | Behavior |
| --- | --- |
| Mobile | View Context Usage opens a 132 px-wide tooltip ordered as Cost, Usage, Token |
| Desktop | The same action opens the right-side Review and Files panel with the Context tab selected |

Mobile tooltip fixtures:

| Metric | Fixture |
| --- | --- |
| Cost | `<fixture-cost>` |
| Usage | `<fixture-percent>` |
| Token | `<fixture-token-count>` |

The desktop Context tab shows session, message count, provider, model, context limit, total tokens, usage, input/output/reasoning/cache tokens, user/assistant message counts, total cost, creation time, last activity, context breakdown, and raw message list.

Android recommendation: use the same 24 dp progress-circle icon button in the title area. Open an anchored popup containing the core fields from the mobile summary and desktop detail panel instead of reproducing the desktop right sidebar, and ensure it is not obscured by the IME or bottom composer.

### 9.4 Session Overflow Menu

| Menu item | Backend behavior |
| --- | --- |
| `Rename` | `PATCH /session/{sessionID}` with body `title` |
| `Share` | `POST /session/{sessionID}/share`; copy an existing link |
| `Archive` | `PATCH /session/{sessionID}`, possibly updating `time.archived` |
| `Delete` | `DELETE /session/{sessionID}`; confirmation is mandatory |

The exploration did not execute final archive or delete confirmation. Android must confirm destructive actions and coordinate optimistic updates between list and detail views.

### 9.5 Confirmation Dialogs

Confirmation dialogs are an exception to the rule against mechanically reproducing desktop dialogs. Actions requiring explicit reconfirmation, including delete, archive, close, and disconnect, should reproduce the OpenCode Web confirmation style rather than use the default Android `AlertDialog` appearance.

For example, the delete-session confirmation remains a centered card at mobile width:

| Element | Web style | Android reproduction |
| --- | --- | --- |
| Scrim | Gray translucent overlay darkening the background | Dialog dim scrim with `dimAmount` around 0.3 |
| Card | White background, 10 px radius, subtle 1 px gray border impression, soft shadow | White `Panel`, 10 dp radius, 1 dp `Border`, light shadow |
| Width | About 640 px maximum on desktop; about 8 px side margins on mobile | `maxWidth = 640dp`, 8 dp side margins on small screens |
| Title row | 16 px medium title at left, 24 px Close button at right | 16 sp medium title; 24 dp/16 dp close-icon visual |
| Body | 13 px text such as `Delete session "Example session"?` | 13 sp body naming the affected object |
| Buttons | Bottom right, 32 px high; transparent Cancel and black primary button with white text | Right-aligned, 32 dp high, 8 dp gap, primary button using black `BorderStrong` background |

Delete-session copy uses title `Delete session`, body `Delete session "<session title>"?`, and confirmation button `Delete session`. The destructive confirmation button follows Web with a black primary button rather than red text; red may still indicate danger in menu items or icons.

## 10. Change Review Page

Selecting the Changes tab shows:

| Element | Observation |
| --- | --- |
| Dropdown | `Git changes` selected; options include `Git changes` and `Last turn changes` |
| Empty state | `No uncommitted changes yet` |
| Network | `GET /vcs/diff?mode=git&directory=...` and `GET /session/{sessionID}/diff?directory=...` |

The fixture project had no uncommitted changes, so expanded diff behavior was not observed.

Current Android implementation: usable diff is in the Changes tab of `SessionDetailScreen` and supports Git changes and session-diff data. The standalone `ReviewRoute` remains a placeholder. If a standalone page later materially improves cross-session review, migrate to a `LazyColumn` file/hunk page. Whether using a tab, full-screen page, or bottom sheet, never position review content below screen coordinate y=843.

## 11. Server Status and Server Management

### 11.1 Status Popover

Pressing Status opens the Server Configuration surface. At mobile width it is about 350 px wide and aligned near the upper right.

Tabs:

| Tab | Content |
| --- | --- |
| `1 server` | `127.0.0.1:4096`, `<fixture-server-version>`, and `Manage servers` |
| `MCP` | `No MCPs configured` |
| `LSP` | `LSPs automatically detected from file types` |
| `1 plugin` | `@example/opencode-plugin@1.0.0` |

### 11.2 Server Management Dialog

Manage Servers opens a Servers dialog:

| Element | Content |
| --- | --- |
| Title | `Servers` |
| Search field | `Search servers` |
| Server row | `127.0.0.1:4096 <fixture-server-version> No username` |
| Bottom button | `Add server` |

Add Server opens a child page:

| Field | Placeholder/default |
| --- | --- |
| Server URL | `http://127.0.0.1:4096` |
| Server name, optional | `Localhost` |
| Username, optional | `opencode` |
| Password, optional | `Password` |
| Submit | `Add server` |

Android recommendation: use a screen-constrained anchored popup for short server status and `ServerListScreen` for management. Use full-width cards. Long-pressing the card body information area may start drag reordering and persist the local server order; interactive edit, delete, health-check, and open controls must not initiate dragging. When the list is empty, show the welcome empty state on the server-list page without adding a separate route, and keep the bottom Add Server action. Use a full form page for adding a server, and return to the server list after a successful add rather than automatically entering project selection. Leave the connection name and the Direct-mode OpenCode service URL empty by default; do not create or imply an OpenCode Server on the device. When the user selects SSH forwarding or frpc STCP visitor, fill an empty service URL with the common tunnel-remote address `http://127.0.0.1:4096`, but never replace a user-entered or existing edit-form value. Use a password field and show a specific error when connection testing fails. The OpenCode service URL must be an HTTP(S) URL with a valid host and no userinfo, query, or fragment. If an old configuration violates this rule, the list, status panel, settings page, and edit page must not echo the original URL or a name derived from it.

Android connection addresses must be configured for the actual runtime environment. Do not treat any emulator, physical-device, or `adb reverse` port mapping as a universal default. Upgrade migration removes only direct-connection records still exactly matching the historical auto-generated `local / Localhost / loopback:4096` values. Preserve every record whose name, address, authentication, or tunnel configuration was edited.

Run ordinary URL and mode-specific form validation before any security confirmation. In Direct mode only, saving a normalized non-loopback `http://` URL with active OpenCode Basic authentication requires an explicit advisory confirmation. Basic authentication is active when the username is nonblank and either a new nonblank password is entered or the edit form retains an existing password alias. Loopback classification is syntactic and performs no DNS lookup: `localhost` and its reserved subdomains, IPv4 `127.0.0.0/8`, IPv6 `::1`, and mapped loopback literals are exempt; LAN addresses, emulator aliases, wildcard addresses, and lookalike domains are not. The dialog explains that Android Keystore protects credentials stored on the device but does not encrypt HTTP traffic. Confirming saves the frozen validated form through the normal credential transaction and permits normal later use; cancelling performs no repository write and leaves the form intact. SSH and STCP modes do not use this warning, and existing saved profiles are not gated when connecting.

### 11.3 Connection Modes and Local-Port Connections

The Android add/edit server form provides mutually exclusive connection modes: Direct, SSH forwarding, and `frpc STCP visitor`. Direct accesses the OpenCode service URL directly. SSH and STCP first create `127.0.0.1:<local-port>` on the device, then rewrite both REST and SSE to the same local-port URL.

With SSH forwarding enabled, OpenCode service URL means the address reachable from the SSH remote host, for example `http://127.0.0.1:4096`. The app's effective connection URL becomes `http://127.0.0.1:<local-forward-port>`.

The first `frpc STCP visitor` version supports local-port mode only. The app starts the STCP visitor, binds `127.0.0.1:<local-bind-port>`, and accesses that local address. The real frp protocol implementation is wrapped by the `:frpc-stcp-visitor` module around a GoMobile AAR. The repository keeps the `frpc-stcp-visitor-go/` wrapper, pinned versions, and auditable downstream patch; local scripts or CI generate the immutable-version AAR without committing it, and Android consumes it through a local Maven repository. Release must verify AAR checksum, API signature, provenance, expected ABIs, stripped state, and 16 KB ELF alignment. If the AAR is unavailable, the UI may save configuration, but connection attempts must return a clear unavailable error.

Missing generated bridge classes produce `GoMobileBridgeUnavailableException`; incompatible bridge classes, methods, or payloads produce `GoMobileBridgeApiMismatchException`. Both are typed, payload-free setup failures. STCP readiness and SSE treat them as permanent rather than repeatedly retrying, and the UI maps them to localized semantic errors instead of displaying reflection details.

STCP must not treat configuration committed as connection ready. The app first waits for a real visitor listener bind in the current frps control epoch, then performs `/global/health` through the local tunnel. Only after both steps may project REST and SSE use the connection. Concurrent first health check, project snapshot, global SSE, and project SSE requests share the same readiness process rather than independently starting visitors or probes.

When the user starts a health check during cold STCP startup, allow bounded waiting and retry for transient connection refused, reset, or timeout. On success, reuse that readiness health result to show the version rather than immediately requesting a second time. Authentication, certificate, address, and configuration errors fail fast with explicit messages. After frps reconnect creates a new control epoch, wait for the listener again and recalibrate `/global/health` before the next REST/SSE connection. Keep existing UI data while disconnected, and never let a late result from an old epoch overwrite the new connection.

Add-server fields:

| Group | Field | Default | Description |
| --- | --- | --- | --- |
| Basic | Connection name | Auto-generated | Optional |
| Basic | OpenCode service URL | Empty for Direct; selecting SSH/STCP fills an empty value with `http://127.0.0.1:4096` | Direct URL or service URL reachable from the tunnel remote; never replaces an existing value |
| OpenCode authentication | OpenCode username/password | Empty | HTTP Basic Auth, separate from SSH credentials |
| Basic | Connection mode | Direct | Mutually exclusive Direct, SSH forwarding, or `frpc STCP visitor` |
| SSH | SSH host | Empty | Required when enabled |
| SSH | SSH port | `22` | May share a row with SSH username |
| SSH | SSH username | Empty | May share a row with SSH port |
| SSH | Authentication method | Private key | Password, private key, or password plus private key |
| SSH | SSH password | Empty | Required when the method includes password; store in Keystore |
| SSH | Private-key source | Paste text | Paste plaintext or select a local file |
| SSH | Private-key text/file | Empty | Store content in Keystore and show only the filename; UTF-8 content is limited to 256 KiB |
| SSH | Private-key passphrase | Empty | Optional; store in Keystore |
| Forwarding | Local forward port | `4096` | Do not auto-change; ask the user to edit if occupied |
| Forwarding | Connection timeout | `10` seconds | Exposed to the user |
| Forwarding | KeepAlive interval | `30` seconds | Exposed to the user |
| Host Key | Host-key verification | Trust on first use and save | Store the first fingerprint in Keystore; reject later mismatches |
| Host Key | Host fingerprint | Empty | May be specified manually; store in Keystore |
| STCP | frps address | Empty | Required when STCP is enabled |
| STCP | frps port | `7000` | Must be within 1-65535 |
| STCP | frps token | Empty | Optional; store in Keystore and never echo plaintext |
| STCP | frpc user | Empty | Optional; passed to the frp session |
| STCP | STCP server user | Empty | Optional; maps to the server-side STCP proxy user |
| STCP | STCP server name | Empty | Required when enabled |
| STCP | STCP secret key | Empty | Store in Keystore and never echo plaintext |
| STCP | Local bind port | `4096` | Binds `127.0.0.1:<port>` for shared REST/SSE use |
| STCP | frp wire protocol | `v1` | Supports `v1`/`v2`; first-version transport is fixed to `tcp` |

Mobile layout may place two short fields on one row, such as OpenCode username/password, SSH port/username, local forward port/timeout, and frps port/STCP local bind port. Private-key text uses a multiline field. A local key file is selected with the Android file picker and read on an IO dispatcher through a fixed buffer capped at 256 KiB; do not retain its file path. Recheck UTF-8 byte size at persistence and JSch authentication boundaries rather than trusting a document provider's declared size. STCP token and secret key use password fields. On the edit page, leaving a saved secret field empty means retain the existing Keystore value.

Server persistence uses a two-phase switch between configuration and credentials. Write new values first to purpose-specific candidate aliases carrying random UUIDs. After all candidates are built, check coroutine cancellation, then enter a non-cancellable commit section that writes `ServerConfig`, rereads to confirm the result, advances the connection configuration epoch, and invalidates SSH/STCP runtime state. If the write throws but the target configuration is persisted, complete as success. Roll back candidates only when non-commit is confirmed. If confirmation read also fails, conservatively retain candidates and report an unknown outcome so a secret that may be referenced is not deleted. After commit, reread all server references outside the configuration lock and best-effort clean old aliases. Existing blank-field semantics remain unchanged: a blank ordinary password or SSH/STCP secret retains the old value, while disabling the corresponding authentication or connection mode removes its configuration reference. The file button says Cancel Selection rather than implying deletion of a stored private key. If SSH host or port changes, do not inherit the old pin: `AcceptNew` performs TOFU again, while `Fingerprint` requires a new fingerprint. TOFU fingerprint persistence follows the same transaction rules.

## 12. Settings

At mobile width, Web settings still use a desktop two-column dialog around `x=16,y=122,w=358,h=600`. The left tab list is about 150 px wide and the right panel about 208 px, forcing all content into narrow columns. This experience should not be copied directly.

Android settings should be a single-column `SettingsScreen`. Its top-level list contains General, Background Running, Keyboard Shortcuts, Servers, Providers, and Models. Each item opens a separate screen. Version information appears at the bottom.

### 12.1 General

| Group | Controls |
| --- | --- |
| Language | Single-row setting opening a bottom sheet with Follow system, English, and Simplified Chinese |
| Permissions | `Auto-accept permissions` switch |
| Terminal Shell | `pwsh` dropdown |
| Display | Switches for Show reasoning summary, Expand shell tool parts, Expand edit tool parts, Show session progress bar checked, and New layout and designs |
| Appearance | Color scheme System, theme `OC-2`, UI font, code font, and Terminal Font |
| System notifications | Agent checked, Permission checked, Error unchecked |
| Sounds | Agent `Steppbopps 01`, Permission `Steppbopps 02`, Error `None 03` |
| Updates | Release notes checked, Check now disabled |

Android stores language preference in DataStore and currently supports Follow system, English, and Simplified Chinese. Switching language uses a shared localized `Context` to refresh Compose copy immediately and uses the same language context for notification titles, bodies, channel names, and channel descriptions. Project paths, session titles, filenames, model/provider names, command output, and server-returned content remain unchanged and are not translated.

Android stores system-notification switches in DataStore with Web-compatible defaults: Agent on, Permission on, Error off. Notification bodies use localized resources and redact error summaries. Agent/Question, Permission, and Error use three stable v2 channels that are created silent by default. Notification taps route to the corresponding project or session; a session is marked viewed only while the app is foreground and that destination is actually visible.

Android stores sound settings in DataStore and reuses 45 Web `.aac` assets. Agent, Permission, and Error sounds each use a bottom sheet to choose None or a sound. Selecting a sound saves immediately and plays one preview through notification audio attributes; choosing None disables OC Deck's sound for that category. Sound and system-notification switches remain independent, but each event has only one sound owner: the app plays its selected sound only while the channel stays at the app-created silent baseline; an explicit Android channel sound takes precedence and suppresses app playback. A blocked, low-importance, or explicitly muted channel is not bypassed by app playback. Legacy `ocdeck_sessions` settings are migrated best-effort into the three v2 channels without overwriting channels that already exist.

### 12.1.1 Background Running Settings

Android adds a Background Running child page to help users check session notifications, background networking, and recent-task retention. It does not copy site-specific text or green visuals from a screenshot and instead uses OC Deck's light card style.

| Item | Behavior |
| --- | --- |
| Allow session notifications | Request runtime notification permission on Android 13+; older versions show that the system allows notifications automatically. Notification content must not expose keys, tokens, request headers, or complete sensitive responses. |
| Ignore battery optimizations | Pressing Request Ignore Battery Optimizations directly launches the system `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` confirmation with data `package:<current-package-name>`. Do not send users to the generic battery-optimization list to find the app. If the system does not support the dialog, show explanatory text only. |
| Keep in recents | Explain that lock/pin entry points differ across vendors and Android has no unified authorization dialog; provide guidance only. |

Recheck notification permission and battery-optimization state whenever the page returns to the foreground. Provide Recheck and Done actions at the bottom. This page changes only Android scheduling of the local app and does not modify OpenCode Server configuration.

### 12.2 Keyboard Shortcuts

The page title is `Keyboard shortcuts`, with a disabled Reset to Defaults and a Search Shortcuts field.

Groups: General, Session, Navigation, Models and Agents, Terminal, and Prompts.

Important shortcuts:

| Function | Shortcut |
| --- | --- |
| Open settings | `Ctrl+,` |
| Open project | `Ctrl+O` |
| Back | `Ctrl+[` |
| Close tab | `Ctrl+W` |
| Add selection to context | `Ctrl+Shift+L` |
| Focus input | `Ctrl+L` |
| Command palette | `Ctrl+Shift+P` |
| Toggle sidebar | `Ctrl+B` |
| New workspace | `Ctrl+Shift+W` |
| Archive session | `Ctrl+Shift+Backspace` |
| Toggle review | `Ctrl+Shift+R` |
| Previous/next session | `Alt+↑/↓` |
| Previous/next message | `Ctrl+Alt+[/]` |
| New session | `Ctrl+Shift+S` |
| Auto-accept permissions | `Ctrl+Shift+A` |
| Open file | `Ctrl+K` |
| Attach file | `Ctrl+U` |
| Toggle MCPs | `Ctrl+;` |
| Switch agent | `Ctrl+.` |
| Select model | `Ctrl+'` |
| Toggle terminal | ``Ctrl+` `` |
| New terminal | `Ctrl+Alt+T` |
| Prompt | `Ctrl+Shift+E` |
| Shell | `Ctrl+Shift+X` |

Android shortcuts primarily serve external keyboards. Keep a read-only shortcut list initially and reserve future support for editable bindings.

### 12.3 Servers

This is consistent with server management in the status popover. The mobile Web layout forces server URL, version, and username into vertical or wrapped text. Android should use full-width cards and allow long-press drag reordering from the card's information body; buttons and icon action areas retain independent click behavior.

### 12.4 Providers

Provider settings load the real server catalog, support search, and divide it into loaded and available providers. The server's `connected` array is authoritative for the loaded state; it does not prove credential validity, network reachability, or a successful model request. Environment- and configuration-managed providers explain their external ownership instead of exposing a misleading disconnect action. Custom providers open their edit page from the provider card.

Custom-provider form:

| Field | Placeholder/description |
| --- | --- |
| Provider ID | `myprovider`; lowercase letters, digits, hyphens, or underscores |
| Display name | `My AI Provider` |
| Base URL | `https://provider-api.example.test/v1` |
| API key | Optional; never record a real value |
| Models | ID `model-id`, name `Display name`, add/remove model |
| Headers, optional | `Header-Name`, `value`, add/remove header |
| Save | Staged server-side save |

Form documentation: `https://opencode.ai/docs/providers/#custom-provider`.

Mobile Web issue: pressing Back from the custom-provider form closes the entire settings dialog instead of returning to the provider list. Android must explicitly maintain the settings child-page back stack.

Current native implementation:

- `/provider` and `/global/config` are immediately projected to safe domain summaries. Raw `key`, `env`, `options`, provider/model headers, and other secret-capable values never enter Compose state or the in-memory Store.
- Connect loads project-aware authentication methods from `/provider/auth`. API and OAuth methods retain their original wire-array index, render server-provided text/select prompts with `when.eq/neq`, and submit only visible validated inputs.
- API keys are written to the OpenCode Server auth store and are never persisted by the Android client. Direct non-loopback `http://` uploads require an explicit confirmation that consumes one frozen validated request; cancellation performs no write.
- OAuth authorize/callback preserve the same optional `directory/workspace` scope. Code mode accepts a manually pasted code; auto mode uses a cancellable client with a bounded ten-minute call timeout. Browser URLs must be HTTP(S), have a valid host, and contain no userinfo. Loopback authorization URLs receive an explicit warning because a browser on the phone normally cannot reach a callback listener running on a remote OpenCode Server.
- Custom providers support up to 100 models and 50 headers. Each add action has a nearby count, becomes disabled at or above its limit, and distinguishes a valid full list from externally loaded overflow without silently truncating rows. Save first stages the provider as disabled, then writes an optional API key, and finally enables it. Credential failure leaves a safely disabled configuration; an unconfirmable final write is reported as an unknown outcome rather than a clean failure. Provider API keys and header values remain in memory only while editing and are cleared after committed, partial, or unknown outcomes.
- OpenCode's global-config PATCH is a deep merge without reliable field deletion. Existing model IDs and header names therefore cannot be removed or renamed in the editor. “Disable and clean credentials” adds the provider to `disabled_providers` before best-effort auth deletion and retains the disabled configuration; it is not represented as physical deletion.
- After a successful or partially committed provider mutation, the client disposes server instances when possible and asks the existing generation/source/transport-validated SSE calibration path to refresh affected active-project capabilities.

Provider catalog/auth-method/OAuth endpoints are instance-scoped and accept optional `directory/workspace`. Credential writes and global-config operations are server-global root-control operations. Project/session entry points preserve the current directory for method discovery and OAuth; entry from top-level Settings has no project scope.

Provider APIs:

| Operation | API |
| --- | --- |
| List | `GET /provider?directory=...&workspace=...` |
| Authentication methods | `GET /provider/auth?directory=...&workspace=...` |
| Write API auth | `PUT /auth/{providerID}`; server-global |
| Delete auth | `DELETE /auth/{providerID}`; server-global |
| Start OAuth | `POST /provider/{providerID}/oauth/authorize?directory=...&workspace=...` |
| OAuth callback | `POST /provider/{providerID}/oauth/callback?directory=...&workspace=...` |
| Read/update custom-provider config | `GET/PATCH /global/config`; server-global and secret-capable |
| Reload provider instances | `POST /global/dispose` |

### 12.5 Models

Models in settings enable or hide models; they do not select the active session model.

| Provider | Visible model fixtures and switches |
| --- | --- |
| provider-alpha | `model-standard` checked, `model-fast` off, `model-reasoning` checked |
| provider-beta | `model-small` checked, `model-large` off |

Android recommendation: use a provider-grouped model-settings list. Product confirmation is needed on whether switches save immediately or require a Save button. The current native client loads real provider/model data from `/provider`; enabled/hidden is stored only in local DataStore and consistently filters both settings and composer model lists. It does not read or write OpenCode Server config. Server information on the model-settings page shows only the server URL. Never display sensitive provider configuration or plaintext keys.

## 13. Composer Interaction Design

### 13.1 Basic Structure

The bottom composer contains an input field, Send, Attach File, agent, model, and reasoning-strength controls. Send is disabled for empty input. Entering fixture text enables Send, but the exploration did not submit it.

The Web UI also showed a `Shell` chip and a Cancel action. The chip may be activated by `/shell` or a shortcut. In the mobile layout, another element at the same layer intercepted Cancel, showing a touch-layer problem in the bottom parameter strip. Android must give a chip's remove action an independent 48 dp touch area.

### 13.2 Slash Commands

Entering `/` opens a command/skill suggestion panel above the composer, approximately from `y=368` to the composer top and around 320 px high, covering the lower message stream.

Fixture entries:

| Type | Examples |
| --- | --- |
| Project commands | `/init`, `/review`, `/example-command` |
| Skill | `/example-command` |
| Built-in commands | `/new`, `/undo`, `/compact`, `/fork`, `/share`, `/open`, `/terminal`, `/model`, `/mcp`, `/agent`, `/workspace` |

Android recommendation: prefer an inline suggestion panel above the composer with search, keyboard up/down selection, and touch selection. Use a screen-constrained bottom sheet only when there are many candidates or complex filters. Selecting a command may insert a chip or text. Only commands matching loaded `/command` data may route to the command API.

### 13.3 @ Mentions

Entering `@` shows only agent mention candidates such as `@explore` and `@general`; it is not a file reference. File context primarily enters through Attach File or `/open`.

Android recommendation: keep a separate mention-panel data source rather than sharing file search. Selecting an agent mention inserts a mention token.

### 13.4 Attach File

Attach File invokes the system file picker rather than a Web-internal file list. After selecting `X:\workspace\sample-project\example.txt`, the composer grows and shows a 64x64 attachment card containing a file icon, filename `example.txt`, and Remove Attachment. Send becomes enabled. The exploration removed the attachment without sending.

Android has two reproduction modes:

| Mode | Use |
| --- | --- |
| Local device picker | Choose a phone-local image/file and convert it to a data URL or upload it |
| Server project-file picker | Choose a file from the OpenCode Server project using `/file`, `/file/content`, and `/find/file` |

The current Web Attach File behavior resembles a local-device picker. When the Android app connects to a desktop OpenCode Server, provide both Choose from Project and Choose from Phone.

The native implementation keeps the two sources explicit. Phone-local attachments are bounded file bytes represented as `data:` parts. Server project-file contexts are separate draft values containing validated relative paths, with at most 10 selected files. Selection does not call `/file/content` unless the user opens preview, and Android does not read or Base64-encode the file merely to attach it. At the sender boundary, OC Deck rebuilds a percent-encoded `file://` URL from the normalized project root and relative path, sends it as a `text/plain` file part, and lets OpenCode Server read and expand it. Project-context-only sends are allowed, and both ordinary prompts and loaded slash commands carry the selected contexts.

### 13.5 Agent Dropdown

The Composer agent dropdown shows only:

| Display | Internal ID | State |
| --- | --- | --- |
| `Build` | `build` | Selected |
| `Plan` | `plan` | Available |

This does not exactly match the complete `/agent` response. The composer likely shows only primary/prompt-capable agents. Android must apply the Web filtering logic rather than expose every returned agent.

### 13.6 Model Picker

Pressing `model-standard` opens a Select Model surface around `x=94,y=470,w=288,h=320`.

Elements:

| Element | Behavior |
| --- | --- |
| Search field | `Search models` |
| `Connect provider` | Enters provider connection |
| `Manage models` | Enters model settings |
| Grouped model list | Grouped by provider |

Visible fixtures:

| Provider | Models |
| --- | --- |
| provider-alpha | `model-standard` selected, `model-fast`, `model-reasoning` |
| provider-beta | `model-small`, `model-large` |

Android recommendation: group by provider, support search, and show a trailing check for the current model. Use an anchored popup when content fits within a constrained small screen; use `ModalBottomSheet` for a long list or complex search/management entries. Connect Provider and Manage Models need real touch targets of at least 48 dp and localized `contentDescription` values.

Current Android implementation: both management actions close the picker first. Connect Provider navigates to `ProviderSettingsRoute(serverId, directory?, workspace?)` and preserves the current project scope when available; Manage Models navigates to `ModelSettingsRoute(serverId)`. Neither remains a placeholder action.

Android should use DataStore keyed by `serverId` to remember the last Composer model and the reasoning strength selected for that model. Restore automatically after process restart when entering the recent-session page or any project session. Before restoration, verify that the model still belongs to a connected provider and the cached variant remains in the model's current `variants`. If the model no longer exists, do not restore it. If the variant is no longer supported, restore the model with Default.

### 13.7 Reasoning Strength/Variant Dropdown

Pressing the bottom reasoning-strength control opens a listbox. Web does not hard-code strengths: it generates options from the current model's `variants` object keys and appends Default in the UI. If `variants` is absent or empty, do not show the reasoning-strength entry.

| Model fixture | Dynamic options |
| --- | --- |
| provider-alpha / `model-standard` | `Default`, `none`, `low`, `medium`, `high`, `xhigh` |
| provider-alpha / `model-reasoning` | `Default`, `low`, `medium`, `high`, `max` |
| provider-beta / `model-small` | `Default`, `none`, `high` |
| provider-beta / `model-basic` | No variants; hide the entry |

This is a model variant/reasoning-strength selector, not a permission mode. Use explicit copy such as Reasoning Strength or Reasoning. Capitalize dynamic display labels, for example `none` as `None`, `max` as `Max`, and `xhigh` as `Xhigh`. When switching models, reset to Default if the old variant is absent from the new model's `variants`, preventing unsupported values from being sent.

### 13.8 Accessibility, Contrast, and Large-Text Behavior

Current Android interaction and acceptance rules:

- Every clickable icon, chip action, picker row, Dock action, and short status control has a real touch target of at least 48 dp even when its visual glyph is smaller.
- Tabs expose `Role.Tab` and selected state; radio choices, checkboxes, and switches expose their matching role and selected/checked state. Expandable status, directory, question, and revert controls provide localized expanded/collapsed descriptions.
- Unread, error, permission, connection, selected, and working states do not rely on color alone. Text, icons, borders, markers, or state descriptions carry the same meaning. Reorderable server cards provide localized TalkBack Move Server Up and Move Server Down custom actions in addition to long-press drag.
- Light and dark palettes target at least 4.5:1 contrast for body/supporting text and at least 3:1 for required non-text indicators and control boundaries. Real text fields and the model-search field use `ControlBorder`; JVM contrast tests cover semantic Diff, Markdown, syntax, chart/context, status, attachment, and control colors.
- Prefer natural text measurement and `heightIn` over fixed text height. Dialogs, server status, settings sheets, suggestion panels, permission/question content, and Composer attachments remain screen constrained and scrollable at compact phone sizes, 200% font scale, and with the IME visible. Attachment and parameter rows scroll horizontally rather than compressing actions out of reach.
- Popups remain focusable and dismissible with system Back. All color, background, border, shadow, and state styling must remain understandable in both themes.

## 14. Send and Composer State Machine

The frontend send logic comes from `session-composer-state`.

### 14.1 Submission Flow

1. `handleSubmit` prevents default submission.
2. Read current prompt text, local-device attachments, project-file contexts, and composer mode.
3. If text, attachments, and context are all empty and the current session is working, abort; otherwise return without sending.
4. Treat the in-memory Store capability snapshot for the current `ProjectKey` as authoritative. Validate the revision carried by the UI, current model, agent, variant, and loaded command. If the revision changed, require the user to retry against the latest selection.
5. On a background thread, perform final attachment-boundary validation: count, per-file and total raw bytes, required metadata, data URL header, Base64 characters/whitespace/padding, and encoded length must agree with declared `sizeBytes`. Separately validate at most 10 project contexts, nonblank IDs, OS-aware relative-path normalization and deduplication, project-root containment, and deterministic `file://` construction.
6. Atomically insert the optimistic message only if the capability revision remains unchanged.
7. If there is no session ID, this is a new session. Optionally create a worktree, then call `POST /session`. After receiving the real ID, add the real session key to the same operation lease before publishing the session, moving messages, and invoking navigation callbacks.
8. Select the API according to mode and input.
9. Ordinary prompts use an optimistic message. On failure, remove only the same message if it is still marked optimistic. If SSE already confirmed that message ID as server-backed, keep it and report an uncertain result to avoid duplicate resend.

### 14.2 New Session Creation

API: `POST /session`

Query: `directory`, `workspace`

Body fields: `parentID`, `title`, `agent`, `model`, `metadata`, `permission`, `workspaceID`

### 14.3 Ordinary Prompt

API: `POST /session/{sessionID}/prompt_async`

Query: `directory`, `workspace`

Body fields: `messageID`, `model`, `agent`, `noReply`, `tools`, `format`, `system`, `variant`, `parts`

The frontend generates `messageID`, creates the local user message and parts, and optimistically inserts them into the stream. Android Store insertion compares the capability revision at the same time. Failure rollback deletes by message ID only while `isOptimistic` is still true, and must not overwrite or delete an SSE-confirmed version that arrived first.

Ordinary prompt parts contain the text part followed by project-file context parts and local-device attachment parts. Each project context uses its stable part ID, `type: "file"`, `mime: "text/plain"`, a safe basename, and a `file://` URL constructed from the current project root. OpenCode Server already supports this part form; no additional endpoint is required.

### 14.4 Slash Command

When input starts with `/` and the command exists in the `/command` response, call:

`POST /session/{sessionID}/command`

Body fields: `messageID`, `agent`, `model`, `arguments`, `command`, `variant`, `parts`

`model` is the string `${providerID}/${modelID}`. Loaded commands carry both project-file contexts and local-device attachments. A local attachment part has this form:

```json
{
  "id": "part_...",
  "type": "file",
  "mime": "...",
  "url": "data:...",
  "filename": "..."
}
```

A project context uses the same file-part shape with `mime: "text/plain"` and a URL such as `file:///E:/repo/src/Main.kt`.

### 14.5 Shell Mode

When composer mode is `shell`, call:

`POST /session/{sessionID}/shell`

Body fields: `messageID`, `agent`, `model`, `command`

The exploration observed a Shell chip but did not submit it. Android should expose prompt mode as explicit state with a one-tap cancel action.

### 14.6 Abort

API: `POST /session/{sessionID}/abort`

When input is empty and the session is working, the submit control and behavior switch to abort. Android may replace Send with Stop.

### 14.7 Worktree

If a new session requests a worktree, call:

`POST /experimental/worktree`

The body is `worktreeCreateInput`.

Related APIs are `DELETE /experimental/worktree` and `POST /experimental/worktree/reset`.

## 15. Permissions, Questions, and Realtime Events

### 15.1 SSE

Global events: `GET /global/event`

Response type: `text/event-stream`

Headers include `cache-control: no-cache, no-transform`, `x-accel-buffering: no`, and `x-content-type-options: nosniff`.

A project event client also exists: `GET /event?directory=...&workspace=...`.

The Android production SSE path uses OkHttp `Call` and `ResponseBody.source()` with a custom bounded reader that parses the byte stream directly instead of the unbounded okhttp-sse event parser. It requests `Accept-Encoding: identity` and rejects any non-identity `Content-Encoding` before `onOpen` without reading the body. Accept only HTTP 200 with Content-Type media type `text/event-stream`, allowing charset parameters. For 204, other non-200 responses, and missing/incorrect Content-Type, close before `onOpen`, do not read the body, and return a typed protocol failure without body data. HTTP 204, non-transient 4xx, content-encoding failures, and Content-Type failures enter `Failed`; 408/409/425/429 and 5xx use the existing bounded backoff retry. Limit each physical/logical SSE line and complete event data to 32 MiB of UTF-8 identity response-body bytes. This size accommodates Base64 echo of one maximum 20 MiB raw attachment plus SSE/JSON framing. Enforce the line-byte limit before converting a complete line to `String` and the cumulative event-data limit before joining full event data. Support LF, CRLF, CR, BOM, comments, and `data/event/id`. Dispatch only on a complete blank line; discard an unterminated pending line/event at EOF. A server `retry` field must not override the client's bounded backoff policy. On overflow, immediately close the response, cancel the Call, and report a typed failure containing no payload. I/O failure caused by explicit handle cancellation must not be reported as a retryable failure. JSON syntax or field-type errors in one event discard only that event and do not close the connection; JVM `Error` still propagates.

Global and project streams are managed by application-level owner leases. The project shell and a session detail reached directly by deep link share one global source. When the last owner releases or force-closes, the stream enters `Closed`; results, callbacks, and retries from old generation/source identities cannot revive it. When the project stream for a `ProjectKey` is `Open` and its transport identity matches the global listener, the project stream is authoritative for project events, and matching global events for that directory/workspace are neither reduced nor buffered. While the project stream has not opened, is retrying, or has failed, global events remain a fallback. If fallback receives LSP/MCP capability changes, the global listener may start safe snapshot calibration using its own generation/source/transport token. A subsequent project `Open` cancels that global calibration and replaces it with project-stream calibration; old results cannot overwrite new state. After project-stream open, reconnect, and foreground resume, an application-level single-flight coordinator loads a REST snapshot. During calibration, it buffers authoritative events in arrival order within bounds. On snapshot success, apply the snapshot and replay events; on failure, retain existing data and replay directly. LSP/MCP capability changes during calibration coalesce into at most one follow-up after the current round. In STCP mode, EventSource and snapshot creation must pass through shared connection readiness. Before `getConnection()` returns, it revalidates native generation/control epoch. Project snapshots and foreground health recheck connection identity after I/O success or failure, discard stale results, and retry within bounds when identity changes. When frps control epoch or server configuration epoch changes, rebuild all global/project streams for that server as one transport-identity change. An open attempt known to be older than current identity must atomically detach and enter the normal retry path protected by generation/desired state rather than remain stuck in `Connecting`.

### 15.2 Permission

| Operation | API |
| --- | --- |
| List | `GET /permission?directory=...` |
| Legacy reply | `POST /permission/{requestID}/reply`, body `reply`, `message` |
| Session permission response | `POST /session/{sessionID}/permissions/{permissionID}`, body `response` |

Web testing confirmed that permission confirmation is not a centered dialog. It is a blocking Dock in the session's bottom composer area. When a permission is pending, the ordinary composer is hidden and the Dock shows a warning icon, `Permission required` title, tool description, pattern list, and three actions: Reject, Always Allow, and Allow Once. Buttons submit to `POST /session/{sessionID}/permissions/{permissionID}` with body `{ "response": "reject|always|once" }`; success returns `true`. The visible Dock still consumes the legacy `/permission` list and `permission.asked` / `permission.replied` SSE event shapes. A typical request contains `id`, `sessionID`, `permission`, `patterns`, `always`, and `tool`.

Android recommendation: in `SessionDetailScreen` bottomBar, show blocking `QuestionDock` first and `PermissionDock` second, replacing the ordinary composer at the same level. Use light/dark theme tokens rather than hard-coded colors. Patterns may scroll and be selectable for copy. All three buttons require at least 48 dp touch targets. Frontend auto-accept calls `permission.respond` with a response such as `once`; if Android later supports auto-accept, filter requests already answered automatically to prevent UI flicker.

### 15.3 Question

| Operation | API |
| --- | --- |
| List | `GET /question?directory=...` |
| Reply | `POST /question/{requestID}/reply`, body `answers` |
| Reject | `POST /question/{requestID}/reject` |

The current `/question` fixture is an empty array. Android should present questions in a bottom sheet with single choice, multiple choice, custom answers, and reject.

## 16. Network API Matrix

Every Retrofit method in `OpenCodeApi` declares an explicit inbound response policy. Ordinary successful JSON/object/list/Boolean/`JsonElement` responses and `/file/content` have two independent 16 MiB boundaries: a request tag drives a network interceptor over encoded response-body octets before `Content-Encoding` decoding, and the Retrofit interceptor lazily bounds the decoded entity. `Content-Length` is only an early rejection; unknown and understated lengths fail at `max + 1`. Successful `Response<Unit>` bodies are closed and discarded without reading. Every non-2xx body is also closed and replaced with an empty entity while preserving the status code, preventing Retrofit from caching or converting sensitive error content. A missing policy fails closed before the request proceeds. Requests without a Retrofit `Invocation`, including the direct session-message transport, attach their dedicated encoded-body policy explicitly.

### 16.1 Startup and Global

| Method | Path | Purpose | Notes |
| --- | --- | --- | --- |
| GET | `/global/config` | Global configuration | Contains sensitive provider configuration and must be redacted |
| PATCH | `/global/config` | Update global configuration | Body is the bare partial config, not wrapped in `config` |
| GET | `/provider` | Provider and model list | May include `directory` |
| GET | `/provider/auth` | Provider authentication methods | Optional `directory/workspace`; method array indexes are protocol values |
| PUT/DELETE | `/auth/{providerID}` | Write/delete provider credentials | Server-global root-control API |
| POST | `/provider/{providerID}/oauth/authorize` | Start provider OAuth | Optional `directory/workspace`; stateful and not automatically retried |
| POST | `/provider/{providerID}/oauth/callback` | Complete provider OAuth | Same scope and method index as authorize; long auto callbacks use a bounded dedicated client |
| GET | `/path` | home/state/config/worktree/directory | May include `directory` |
| GET | `/project` | Recent/known projects | Project includes `id/worktree/vcs/icon/time/sandboxes` |
| GET | `/global/health` | Health check | Requested periodically |
| GET | `/global/event` | Global SSE | Realtime synchronization |
| POST | `/global/dispose` | Dispose | Related to server settings; use cautiously |

### 16.2 Project Loading

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/path?directory=...` | Project path context |
| GET | `/lsp?directory=...` | LSP list; currently an empty array |
| GET | `/mcp?directory=...` | MCP list; currently an empty object |
| GET | `/session?directory=...&workspace=...&roots=true&limit=...` | Root-session ordered prefix for the shared Store window |
| GET | `/agent?directory=...` | Agent list |
| GET | `/config?directory=...` | Project configuration; redact sensitive fields |
| PATCH | `/config?directory=...` | Update project configuration; body `config` |
| GET | `/session/status?directory=...` | Session working status |
| GET | `/vcs?directory=...` | Branch information |
| GET | `/command?directory=...` | Slash-command list |
| GET | `/permission?directory=...` | Pending permissions |
| GET | `/question?directory=...` | Pending questions |

### 16.3 Sessions

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/session/{sessionID}` | Session details |
| PATCH | `/session/{sessionID}` | Update title/metadata/permission/time |
| DELETE | `/session/{sessionID}` | Delete session |
| GET | `/session/{sessionID}/message?limit=200` | Message list; bypass Retrofit error-body caching, stream-decode 2xx JSON with a 64 MiB limit, and do not read non-2xx bodies |
| GET | `/session/{sessionID}/message/{messageID}` | One message |
| DELETE | `/session/{sessionID}/message/{messageID}` | Delete message |
| DELETE | `/session/{sessionID}/message/{messageID}/part/{partID}` | Delete message part |
| POST | `/session/{sessionID}/fork` | Fork session; body `messageID` |
| POST | `/session/{sessionID}/abort` | Abort execution |
| POST | `/session/{sessionID}/init` | Initialize the AGENTS.md flow |
| POST | `/session/{sessionID}/share` | Share |
| DELETE | `/session/{sessionID}/share` | Stop sharing |
| POST | `/session/{sessionID}/summarize` | Summarize/compact; body `providerID/modelID/auto` |
| POST | `/session/{sessionID}/prompt_async` | Send an ordinary prompt |
| POST | `/session/{sessionID}/command` | Send a slash command |
| POST | `/session/{sessionID}/shell` | Send a shell command |
| POST | `/session/{sessionID}/permissions/{permissionID}` | Reply to pending permission; body `response=reject|always|once` |
| POST | `/session/{sessionID}/revert` | Create or move reversible boundary; body `messageID/partID`; directory/workspace use request headers |
| POST | `/session/{sessionID}/unrevert` | Clear boundary and restore all messages; directory/workspace use request headers |
| GET | `/session/{sessionID}/diff` | Session diff |
| GET | `/session/{sessionID}/todo` | Session todo |
| GET | `/session/{sessionID}/children` | Child sessions |

### 16.4 Files, Find, VCS, and PTY

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/find` | Text search; query `pattern` |
| GET | `/find/file` | File/directory search; query `query/dirs/type/limit` |
| GET | `/find/symbol` | Symbol search |
| GET | `/file` | Directory listing; query `path` |
| GET | `/file/content` | File content; query `path` |
| GET | `/file/status` | File status |
| GET | `/vcs` | Branch information |
| GET | `/vcs/status` | VCS status |
| GET | `/vcs/diff` | Diff; query `mode/context` |
| GET | `/vcs/diff/raw` | Raw diff |
| POST | `/vcs/apply` | Apply patch; body `patch` |
| GET | `/pty/shells` | Shell list |
| GET | `/pty` | PTY list |
| POST | `/pty` | Create PTY; body `command/args/cwd/title/env` |
| DELETE | `/pty/{ptyID}` | Delete PTY |
| GET | `/pty/{ptyID}/connect-token` | Connect token |
| GET | `/pty/{ptyID}/connect` | PTY connection |

Additional file constraints: `/file` returns only direct children of the requested directory with required fields `name/path/absolute/type/ignored`. `/file/content` returns either `{ type: "text", content }` or `{ type: "binary", content, encoding: "base64", mimeType }`. The current server trims text, returns successful empty text for a missing file, and still returns an empty array from `/file/status`. Android does not depend on `/file/status`. The endpoint passes the common 16 MiB encoded response-body boundary and the lazy 16 MiB decoded Retrofit boundary, then keeps a reader-level decoded check before JSON deserialization as defense in depth.

## 17. Data Model Summary

### 17.1 Project

Fields: `id`, `worktree`, `vcs`, `icon.color`, `time.created`, `time.updated`, `sandboxes`.

Recommended Android-local additions: `normalizedPath`, `displayName`, `lastOpenedAt`, `isDuplicateAlias`.

### 17.2 Session

Fields: `id`, `slug`, `projectID`, `directory`, `path`, `summary.additions/deletions/files`, `cost`, `tokens.input/output/reasoning/cache.read/cache.write`, `title`, `agent`, `model.id/providerID/variant`, `version`, `time.created/updated`.

Current session fixture:

| Field | Value |
| --- | --- |
| id | `ses_fixture_001` |
| title | `Example session` |
| agent | `build` |
| model | `provider-alpha/model-standard` |
| variant | `xhigh` |
| tokens.input | `<fixture-input-tokens>` |
| tokens.output | `<fixture-output-tokens>` |
| tokens.reasoning | `<fixture-reasoning-tokens>` |
| tokens.cache.read | `<fixture-cache-read-tokens>` |

### 17.3 Agent

`/agent` returns multiple agents. Fixture `build` has description `The default agent. Executes tools based on configured permissions.`, mode `primary`, `native=true`, and permissions including `{permission:"*", pattern:"*", action:"allow"}`.

The Composer selector shows only Build and Plan while retaining internal IDs `build` and `plan`; apply the Web filtering logic.

### 17.4 Provider

`/provider` returns `{ all, default, connected }`. Current servers use an `all` array, while tolerant parsing also accepts object and `providers` compatibility forms. `connected` is authoritative for whether a provider is loaded; never infer it from `source`.

Raw Provider objects may contain `id/name/source/env/key/options/models`, and model objects may contain headers. Android immediately projects them to ID/name/source, loaded state, model count, and a custom-config marker. Never expose or retain `env`, API keys, headers, auth tokens, URLs from secret-capable options, or the raw response.

`/provider/auth` returns provider-keyed method arrays. Unknown methods/prompts may be skipped, but every supported method retains its original array index for authorize/callback. OAuth URLs pass the external HTTP(S) URL policy before browser launch. `/global/config` is likewise projected to custom-provider identity, model identity, header names, and disabled state without retaining API keys or header values.

## 18. Mobile Adaptation Issue List

| Issue | Web observation | Android handling |
| --- | --- | --- |
| Settings columns are too narrow | Right panel is about 208 px and wraps content | Single-column full-screen settings |
| Server list wraps badly | Address/version/username are compressed | Full-width cards |
| Bottom review area is invisible | Nearly entirely below y=843 | Standalone page or bottom sheet |
| Duplicate paths | `\` and `/` produce duplicate projects | Normalize and deduplicate paths |
| Drawer pointer interception | Off-screen DOM occasionally intercepts input | Remove it from hit testing after close |
| Composer-chip cancellation | Cancel is intercepted by a sibling layer | Independent touch target and z-index |
| Provider child-page back | Back closes the whole settings dialog | Explicit `NavBackStack` |
| New session inherits Shell | New `/session` page still shows Shell chip | Reset composer by default or persist drafts according to product policy |
| Attachment source is unclear | Web invokes the system file picker | Distinguish phone-local files from server project files |
| Popover placement on small screens | Model/status/tooltip use desktop surfaces | Constrained anchored popup for short lists/summaries; bottom sheet for long lists, search, and complex actions |
| Selection semantics are implicit | Desktop visuals may indicate selection without an accessibility role | Expose Tab/RadioButton/Checkbox/Switch roles and selected/checked state |
| Status depends on color | Unread/error/permission/connection colors may be ambiguous | Pair color with text, icons, borders, markers, or state descriptions |
| Large text or IME hides actions | Fixed-height desktop surfaces can clip content | Natural measurement, bounded vertical/horizontal scrolling, 200% font and IME validation |
| Drag-only server ordering | Long-press drag is not sufficient for TalkBack | Add localized Move Up/Move Down custom actions |

### 18.1 Manual UI Validation Matrix

There is currently no `app/src/androidTest` suite and no emulator/instrumentation job in CI. Until that gate is added, interaction changes must be checked manually against this matrix:

| Environment | Checks | Expected result |
| --- | --- | --- |
| Compact portrait phone at 200% font scale | Confirmation dialogs, server status, settings sheets, suggestion panels, permission/question UI, and attachment rows | Content remains screen constrained and scrollable; titles and primary actions remain reachable without fixed-height clipping |
| IME open on a compact phone | Bottom Composer, inline suggestions, model/agent/variant popups, attachment strip, system Back | Composer remains fixed above insets; overlays are not hidden; Back dismisses the focused overlay before leaving the screen |
| TalkBack enabled | Icon labels, tabs and selection controls, expanded/collapsed controls, unread/error/permission/connection states, server ordering | Roles and states are announced; important meaning is not color-only; server cards expose Move Up/Move Down actions |
| Light and dark themes | Body/supporting text, fields, borders, selected/error/status indicators, Diff/Markdown/code, image-title scrim | Text targets 4.5:1 and required non-text boundaries/indicators target 3:1; no one-theme hard-coded styling loses meaning |
| Composer model picker | Search, provider grouping, current selection, Connect Provider, Manage Models | Rows retain 48 dp targets; management actions close the picker and open the real provider/model settings routes |
| Provider settings and authentication | Search, loaded/available groups, dynamic text/select prompts, API-key keyboard, OAuth browser/code/cancel, cleartext and disconnect confirmations, Custom Provider multi-row editing | Secret values are not echoed after dismissal; sheets and forms remain scrollable with IME/200% text; Back dismisses the focused surface; loaded/disabled/error states do not rely on color alone |

## 19. Native Android Implementation Recommendations

### 19.1 Technical Structure

Use Kotlin and Jetpack Compose.

| Layer | Responsibilities |
| --- | --- |
| Network | Retrofit/OkHttp, SSE client, shared `directory/workspace` queries |
| Repository | `ProjectRepository`, `SessionRepository`, `ConfigRepository`, `ProviderRepository` |
| Store | In-memory Store plus optional Room cache; incrementally updated by SSE |
| UI | Compose screens with ViewModel `StateFlow` |
| Security | Android Keystore for server credentials and redacted logging |

### 19.2 Navigation Graph

Current screens and route capabilities:

| Screen/capability | Parameters and state |
| --- | --- |
| `ProjectPickerScreen` | serverId |
| `ProjectShellScreen` | projectDirectory |
| `SessionListDrawerContent` | projectDirectory |
| `SessionDetailScreen` | projectDirectory, sessionId; new session reuses `"new"` rather than a separate `NewSessionScreen` |
| `ReviewRoute` | projectDirectory, optional sessionId; remains a placeholder while actual diff is in the session Changes tab |
| `SettingsScreen` | serverId |
| `BackgroundRunSettingsScreen` | serverId |
| `ProviderSettingsScreen` | serverId, optional project directory/workspace scope |
| `CustomProviderFormScreen` | serverId, optional project directory/workspace scope, optional providerId for edit |
| `ModelSettingsScreen` | serverId |
| `ServerListScreen` | none |
| `AddServerScreen` | none |

All navigation between full-screen pages uses consistent slide transitions: forward navigation slides the new page from right to left while the current page exits left; Back slides the previous page in from the left while the current page exits right. Bottom sheets, drawers, and dialogs use their own standard expand/dismiss animations rather than full-screen transitions.

### 19.3 UI Components and Interaction Capabilities

Component function names do not have to end in `*Sheet`. Choose inline panel, anchored popup, Dock, or `ModalBottomSheet` based on content length, search needs, and available screen space.

| Component/capability | Key points |
| --- | --- |
| `BottomComposer` | Input, attachments, agent/model/variant, send/stop, mode chip |
| Command suggestions | Inline panel above composer; slash commands, skill badge, shortcut hint |
| Mention suggestions | Inline panel above composer; agent mentions with a data source separate from file search |
| Model picker | Search, provider grouping, current check; constrained popup or sheet for longer lists; Connect Provider and Manage Models navigate to real settings routes |
| Agent picker | Build/Plan with IDs build/plan; extensible later |
| Variant picker | Dynamic Default plus current model `variants`; hidden when none exist |
| `SessionMessageCard` | User/assistant/tool/error multipart rendering with 48 dp reset and copy actions for user messages |
| `SessionRevertDock` | Reverted count, first preview, incremental restore, restore all, and new-branch submission hint |
| Context usage | Token, usage, and cost; anchored popup for short summary |
| `PermissionDock` | Blocking bottom permission confirmation with tool description, patterns, Reject/Always Allow/Allow Once |
| Question interaction | Multiple questions, multi-select, custom input; use sheet for longer content |
| `ProjectFilePanel` | Project-level right overlay, lazy directories, file search, single-page tree/content navigation |
| `OpenCodeCodeViewer` | Read-only text, line numbers, Prism4j highlighting, vertical virtualization, and horizontal scrolling for long lines |
| Accessibility semantics | 48 dp targets, selection roles, localized expanded/collapsed descriptions, non-color status cues, and TalkBack server reorder actions |

### 19.4 State Synchronization

Android must handle both pulls and events:

1. On first project entry, fetch `/path`, `/provider`, `/mcp`, `/session`, `/agent`, `/config`, `/session/status`, `/vcs`, `/command`, `/permission`, and `/question` in parallel.
2. Establish `/global/event` or project `/event` SSE.
3. Reduce events such as `session.created`, `session.updated`, `session.deleted`, and `permission.asked` into the Store.
4. Call `/global/health` periodically or on foreground resume.
5. Use optimistic UI for prompt sends and roll back on failure.
6. Synchronize the revert marker through `session.updated`. Temporary revert changes only the message projection; after sending a new branch, `message.removed` and `message.part.removed` perform permanent Store cleanup.

STCP adds a shared connection barrier before steps 1 and 2: `StartSession -> EnsureVisitor -> WaitVisitorReady -> /global/health`. Calls in the same tunnel generation share one single-flight. The same control epoch can be reused; after reconnect to a new epoch, recalibrate. This barrier applies only to STCP; Direct and SSH keep their existing semantics.

SSE open, close, and retry operations carry owner lease, generation, source, and transport identity checks. Late tasks after close cannot republish `Open`. Snapshot failure cannot block EventSource reconnect, and each recovery runs one coalesced REST calibration. Calibration buffers events under both count and estimated-byte limits; on overflow, cancel the snapshot and replay immediately in order rather than allow unbounded memory. Capability changes during an active calibration set dirty only, causing at most one additional round; replay itself must not create an infinite calibration loop. Foreground resume merges health/readiness checks by server. If transport identity changes, rebuild every stream still desired for that server; otherwise retain open streams and start one coalesced calibration per project. Direct ViewModel project snapshots use project-data revision CAS and a latest-request gate so stale refreshes cannot write loading, error, or final data. Message GET uses a separate message revision. During concurrent SSE, merge historical and realtime messages/parts, prioritize realtime text and delta, preserve local optimistic messages and parts omitted by an equal-revision REST replacement, rebuild empty realtime text from final effective parts, and use deletion revision so old GET results cannot resurrect removed message/part/session data. Redact errors before writing them to Store/UI. After workspace support is enabled, controlled project-state keys include both directory and workspace; Windows drive-letter forms compare case-insensitively through the comparison key. Global connection state remains isolated by `serverId`. Optimistic insertion, materialization, move, acceptance, and rollback for a prompt must stay in the same workspace branch.

Repository calls classify failures as `OpenCodeFailure`; SSE state stores `SseFailureReason`; snapshot coordination returns `ProjectSnapshotOutcome.Success` or `.Failure`. UI converts semantic reasons to localized `UiText.Resource` values and uses the current operation's localized fallback only for unknown failures. Cancellation and JVM `Error` propagate. Bridge unavailable/API-mismatch failures and inbound-policy violations are permanent setup/protocol failures, not transient reconnect signals.

### 19.5 Security and Redaction

The following data must be redacted:

| Data | Handling |
| --- | --- |
| `/global/config` Provider auth/options/headers | Immediately project to safe identity/model/header-name/disabled summaries; do not retain the raw response in UI, Store, or logs |
| `/config` provider/env/auth | Do not show in UI; log as `<redacted>` |
| Provider API keys and custom-header values | Password fields; keep only in memory while editing, send to OpenCode Server auth/global config, never persist in Android Keystore, and clear after committed/partial/unknown outcomes; Direct non-loopback HTTP requires frozen confirmation |
| Provider OAuth URL and callback | Allow only HTTP(S) with a valid host and no userinfo; preserve scope/method index, bound auto callback to ten minutes, allow cancellation, and warn for remote loopback topology |
| SSH/STCP tunnel credentials | Password fields, Keystore storage, logs `<redacted>` |
| SSH host fingerprint | Verify before user authentication through `HostKeyRepository` or equivalent; never disable strict verification and validate afterward |
| Server base URL | Only HTTP(S) with no userinfo/query/fragment; do not echo invalid old values; redact URL userinfo/fragments in free-text logs |
| SSH private-key file/text | Bounded read on IO dispatcher, at most 256 KiB UTF-8; revalidate before save and JSch |
| frp native state/error | Redact tokens, secrets, and raw config before the Go/Kotlin boundary; provenance and API signatures contain no credentials |
| Request headers | Never include in crash reports |
| URI/ResponseBody/Base64/native data | Ordinary Retrofit/file responses have separate 16 MiB encoded-body and decoded-entity limits; session-message direct responses have separate 64 MiB limits; SSE requests identity encoding and limits lines/events to 32 MiB of that representation. These limits do not equal heap peaks; converters and concurrent snapshots can still allocate substantially more. `/file/content` retains its reader-level decoded defense. Attachments also limit per-file size, count, and total bytes. Continue endpoint-by-endpoint audits of other large REST responses. |
| Sensitive or large value-object summaries | Override `toString()` with structural counts/states only; omit credentials, URLs/endpoints, aliases, paths, prompts, Base64, SSE payloads, and tool output; use exactly `<redacted>` for sensitive fields without changing serialization, `copy`, equality, or hashing |
| Share links | Expose only when the user explicitly copies or shares |

## 20. Current Capability and Acceptance Status

| Area | Status | Current acceptance notes |
| --- | --- | --- |
| Recent projects | Available | Selects normalized paths without slash-style or Windows drive-letter-case duplicates; navigation is independent of best-effort ordered DataStore recording, and the drawer rail exposes same-server MRU projects with bounded scrolling and project-home switching |
| Project loading | Available | Shows path, branch, a shared Store-backed root-session window with network Load More/retry/end state, and composer |
| Session details | Available | Shows title, messages, context usage, and session menu |
| Project files | Available | File panel, lazy directories, search, bounded text/image/unpreviewable states, preview, and up-to-10 whole-file Composer context selection are available |
| Change review | Partially complete | Session Changes tab displays Git/session diff; standalone Review route is still a placeholder |
| Composer | Core available | Slash/@/local attachments/project-file contexts/agent/model/variant, context-only submission, authoritative Store capability checks, loaded-command routing, final attachment/context checks, and send/abort/revert single-flight are available; Shell mode is not implemented |
| Session reset | Available | Supports revert/unrevert, incremental/all restore, new-branch projection, attachment-only or project-context-only reset, and recoverable-content integrity failures |
| New session | Available | Reuses `SessionDetailScreen` `"new"`; creates the session on first send |
| Settings | Partially complete | General, Background, Servers, Providers, Custom Provider, and Models use single-column routes; complete shortcuts and some Web settings remain |
| Server | Core available | Mutually exclusive Direct/SSH/STCP, STCP readiness, typed permanent bridge setup failures, pre-authentication SSH host-key validation, URL constraints, and 256 KiB private-key boundary are implemented |
| Provider | Core available | Real safe-projected list/search, dynamic API/OAuth authentication, disconnect, capability refresh, and staged multi-model/header Custom Provider persistence are connected; real-provider compatibility, remote loopback OAuth topology, and device interaction still require validation |
| Model settings | Partially complete | enabled/hidden is a local per-server filter and does not modify OpenCode Server config |
| SSE | Core available | Identity content encoding, custom streaming OkHttp Call reader, 200 + `text/event-stream` gate, blank-line dispatch/EOF discard, 32 MiB line/event limits, owner leases, terminal close, generation/source/transport checks, exponential backoff, snapshot single-flight, bounded event replay, and application foreground recovery are implemented; long real-server outages, process foreground/background transitions, and STCP epoch switching still require validation |
| STCP concurrency and recovery | Core available | Readiness, generation/control epoch, and AAR gates exist; real-device native loading and STCP loop testing remain |
| Failure handling | Core available | Repository/SSE/snapshot paths retain typed semantic reasons, map them to localized resources with operation fallbacks, and propagate cancellation/JVM `Error` |
| Mobile UI and accessibility | Core available | 48 dp targets, matching selection roles, localized expanded/collapsed descriptions, non-color status cues, TalkBack server reorder actions, light/dark contrast tests, and bounded large-text/IME layouts are implemented; device instrumentation is not yet a CI gate |
| Security and input boundaries | Core available | Keystore, structured/free-text Redactor, safe structural summaries, server-URL userinfo prevention, bounded private-key read, attachment data URL/budget checks, separate 16 MiB Retrofit/file encoded and decoded limits, separate 64 MiB direct-OkHttp session-message limits, and 32 MiB identity-SSE limits exist. These limits do not equal heap peaks; other large REST responses, native returns, and real-device paths need continued audit and validation. |

## 21. Next Implementation Priorities

| Priority | Work |
| --- | --- |
| High | Validate long real-server SSE outages, foreground/background transitions, duplicate global/project events, and STCP epoch switching |
| Medium | Validate Provider management against supported real server/provider versions, especially remote loopback OAuth, long-callback cancellation, and partial/unknown custom-config outcomes |
| Medium | Decide the standalone Review route and implement session share |
| Medium | Continue auditing native/image-decode and other external-input boundaries, and validate SSH/STCP plus large-attachment failures on real devices |
| Medium | Complete Settings and remaining loading/empty/error retry states |
| Medium | Add device instrumentation for the compact-screen, 200% font, IME, TalkBack, light/dark, and model-navigation matrix |
| Later | Shell mode, workspace/worktree, PTY/terminal |
| Conditional | Room, Hilt, and business multi-module split only after an explicit need and approval |

Historical P0/P1/P2 labels are retained only to understand feature evolution and no longer define current completion status.
