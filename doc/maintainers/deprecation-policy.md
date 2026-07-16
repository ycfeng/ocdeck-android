# Deprecation Policy

[简体中文](deprecation-policy.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

OC Deck is pre-1.0. Interfaces and behavior can change, but changes that affect users, persisted configuration, supported connection paths, or documented workflows must still be announced and migrated deliberately.

## Scope

This policy applies to:

- User-visible features and workflows.
- Persisted settings, server profiles, credential references, and local data formats.
- Supported Android versions, ABIs, OpenCode Server compatibility, and connection modes.
- Publicly documented build, test, release, and maintenance procedures.
- Stable bridge APIs and immutable bridge artifact coordinates.

Internal implementation details with no user, integrator, persisted-data, or documentation impact do not require a public deprecation period.

## Normal Pre-1.0 Notice Period

For a normal pre-1.0 deprecation, announce it at least one public release before removal or incompatible behavior becomes effective. A feature deprecated in one public version may be removed no earlier than the next public version, unless a longer period is explicitly promised.

The announcement must state:

- What is deprecated and why.
- The earliest version in which removal or incompatible behavior may occur.
- Who is affected and how to detect affected state.
- A migration path, replacement, export path, or explicit statement that no replacement exists.
- Any backup, rollback, server, credential, or signing-continuity implications.

## Urgent Exceptions

An immediate or shortened removal is permitted only when delay would materially worsen a security vulnerability, privacy exposure, legal or licensing violation, signing compromise, destructive data risk, or externally imposed platform breakage.

For an urgent exception:

- Minimize the affected surface rather than bundling unrelated breaking changes.
- Explain the exception without disclosing exploit details or sensitive material.
- Provide the safest feasible migration, recovery, or rollback instructions.
- Record the decision in the changelog, release notes, and migration guidance for the affected release.

## Required Records

Every public deprecation or removal must be recorded in all applicable locations:

- `CHANGELOG.md` under `Deprecated`, `Removed`, `Changed`, or `Security`.
- `release-notes/vX.Y.Z.md` under `Breaking changes` and `Deprecated and removed`.
- A migration section in the release notes or a linked, versioned migration document.
- User-visible warning or validation when silent continuation could lose data, credentials, connectivity, or access.

Pull-request labels and GitHub-generated notes are supplementary and do not replace these records.

## Migration Safety

- Never silently discard user-modified server configurations, credentials, project references, or drafts.
- Migrations must distinguish historical generated defaults from user-modified values.
- Credential rotation must preserve transactional alias and cleanup rules; deprecation is not permission to weaken secret handling.
- Bridge bytes must never change under an existing Maven coordinate. Publish a new `BRIDGE_VERSION` and document the migration.
- Changes that break update installation or signing continuity require an explicit release-blocking review.

## Removal Review

Before removal, confirm that the notice period has elapsed, migration guidance is still correct, telemetry or user data was not required to make the decision, tests cover old-state handling where persisted data exists, and all documentation links point to the replacement or final status.

## Current Deprecations

None / 无.
