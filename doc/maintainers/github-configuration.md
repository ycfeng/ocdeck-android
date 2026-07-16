# GitHub Repository Configuration

[简体中文](github-configuration.zh-CN.md)

This English document is canonical. The Chinese document is a convenience translation.

Repository files cannot enable or prove GitHub server-side settings. Administrators must verify the following against the canonical repository, <https://github.com/ycfeng/ocdeck-android>, and keep the settings valid while accepting external contributions or publishing releases.

## External Contribution Intake

External pull requests and documentation contributions are accepted. GitHub Private Vulnerability Reporting is the repository's shared private intake for security reports and Code of Conduct complaints. Administrators must keep it enabled, verify access from an account without repository administration privileges, and ensure conduct reports are handled under `CODE_OF_CONDUCT.md` rather than the security disclosure process.

## Required Repository Features

- Set `main` as the default branch and enable Issues.
- Enable GitHub Private Vulnerability Reporting and verify <https://github.com/ycfeng/ocdeck-android/security/advisories/new> from an account without repository administration privileges for both security reports and private Code of Conduct complaints.
- Create the labels used by issue triage and `.github/release.yml`: `bug`, `enhancement`, `documentation`, `dependencies`, `ci`, `needs-triage`, `release-notes/breaking`, `release-notes/security`, `release-notes/feature`, `release-notes/fix`, `release-notes/compatibility`, `release-notes/migration`, `release-notes/maintenance`, and `release-notes/skip`.
- Configure a `release` Environment with required reviewers, signing secrets, and the fixed release certificate SHA-256 variable described in the release guide.
- Enable immutable releases where available.

## Rulesets

- Require the CI status check from `.github/workflows/ci.yml` before merging to `main`.
- Require pull-request review and Code Owner review for protected paths.
- Block force pushes and branch deletion on `main`.
- Restrict release tag creation and updates for `v*`; published release tags must not be moved.
- Keep workflow permissions read-only by default. Grant scoped `contents: write` only to `prepare-notes`, which calls GitHub's release-notes API for tag events, and `publish`, which creates the verified release.

With a single maintainer, do not create an approval rule that permanently prevents that maintainer from merging. Record any temporary exception and revisit the rules when another maintainer receives write access.

## Verification

1. Open each issue form as a non-maintainer and confirm required fields block an empty submission.
2. Confirm blank issues are hidden for ordinary users.
3. Open a test pull request that changes a protected path and confirm `@ycfeng` is requested through CODEOWNERS.
4. Confirm the expected labels exist with exact spelling and generated release notes classify sample pull requests correctly.
5. Run a manual Release dry-run on `main` and review both the signed artifacts and prepared release-notes artifact.
6. Before the release tag, complete and record the target-device native-load, 16KB page-size, and real STCP checks in the release checklist.

Do not consider this configuration complete based only on repository files or screenshots. Verify the live settings and access paths directly.
