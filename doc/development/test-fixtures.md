# Test Fixtures

[简体中文](test-fixtures.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

## Current Approach

The repository currently has no shared `fixtures/` or `testdata/` directory. Tests generally use small inline JSON documents, in-memory fakes, local OkHttp test responses, synthetic byte streams, and programmatically generated boundary data. Keep that approach unless a shared fixture is demonstrably clearer and reusable.

Fixtures are test inputs, not captured production data. OC Deck original fixtures and their documentation use the same repository [MIT License](../../LICENSE). A fixture copied or adapted from a third party must retain its source, license, and required notice, and must be reviewed before it is added.

## Data Rules

- Use synthetic identifiers such as `ses_123`, `msg_1`, and non-routable or local example endpoints.
- Use unmistakably synthetic secret values only when a test must prove redaction or credential handling. Assert that those values do not appear in errors, logs, aliases, serialized configuration, or `toString()` output.
- Use `<redacted>` in expected user-facing output. Never paste a real API key, password, token, cookie, private key, provider header, environment value, host fingerprint, or signed URL.
- Replace private project paths and names with minimal examples. Do not copy local user directories, source code, prompts, session content, or complete server responses into tests.
- Keep timestamps, UUIDs, ordering, random seeds, and locale-sensitive values deterministic.
- Include unknown JSON fields where forward-tolerance is part of the behavior under test.

## Boundary Fixtures

Generate large or adversarial inputs in the test instead of committing large binary or text files.

- Test exact limits and `max + 1` for SSE lines/events, session-message bodies, file content, Base64, attachment budgets, URI input, native return values, and private keys.
- Use chunked or infinite synthetic sources to prove that readers stop without unbounded allocation.
- For cancellation tests, use controllable blocking fakes rather than sleeps or external services.
- For race and generation tests, expose deterministic barriers or callbacks so late-result ordering is reproducible.
- Keep malformed payloads minimal enough that the expected failure is obvious.

## File Fixtures

Add a file fixture only when inline data would obscure the behavior, byte-for-byte preservation matters, or several tests share the same input.

When a file fixture is justified:

1. Place it under the owning module's test resources or a narrowly named test-data directory.
2. Use a descriptive filename that states the scenario rather than a real project or customer name.
3. Document provenance and license if any bytes are not original synthetic test data.
4. Keep the file as small as possible and review it for secrets and personal data.
5. Load it through a bounded path when the production behavior being tested is bounded.

Do not commit generated AARs, APKs, keystores, private keys, raw traffic captures, full logs, or production database exports as fixtures.

## Updating Fixtures

- Update fixtures in the same change as the behavior or schema change that requires them.
- Review the semantic diff; do not accept a regenerated golden file only because a test command produced it.
- Update both English and Chinese documentation if the fixture policy or public test procedure changes.
- Run the focused test and the standard module gate after any fixture change.
