# GitHub issue, merge and APK release workflow

Last reviewed: 2026-08-09

This document is the hand-off for continuing Minimum work in GitHub. The canonical implementation
status is [PROJECT_STATUS.md](PROJECT_STATUS.md); this file explains how a field report becomes an
Issue, how a fix is merged, and what is required before publishing an APK. A successful local debug
build is useful evidence, but it is not a release approval.

## Repository and remotes

- GitHub project: `https://github.com/awatchar/minimum`
- Working branch: `agent/minimum-foundation`
- `github` is the project push remote. Keep `origin` pointed at the upstream GitLab Mumla history.
- PR #1 is the current draft integration point. Do not merge it without the administrator's explicit
  approval and a fresh review of the release gate.

## Opening a field Issue

Use one of the repository's three Issue forms rather than an unstructured report:

- **Bug report** for Android, portal, managed config, provisioning, APRS, build/release or docs.
- **Device acceptance result** for a structured T56/T99/generic PASS, FAIL, PARTIAL or BLOCKED run.
- **Feature request** for a focused operator/administrator outcome and acceptance criteria.

Blank Issues are disabled. Security reports go to the private GitHub Security Advisory link in the
Issue chooser. A useful report includes:

1. A short observed-versus-expected title.
2. Device model, Minimum Device ID, Android API, app version/commit and Config Profile. Never put
   server passwords, access tokens, bearer tokens, private keys, IMEI/serial numbers or exact private
   coordinates in an Issue.
3. Reproduction steps, frequency, timestamps and the smallest relevant logcat/portal response.
4. Whether the behavior occurs with the display on/off, network type, selected channel and radio
   state (Connecting, Ready, RX or TX). Redact channel paths when they disclose private information.
5. A clear acceptance result and any safe workaround.

For APRS reports, include the public Object name and APRS-IS/APRS.fi timestamp only. Do not attach
raw GPS traces or Health payloads unless they have been redacted and the operator has approved their
publication. For PTT or reconnect reports, state whether a transmission was supervised and whether
the server confirmed receipt; local packet handoff is not a server receipt.

## Triage and implementation

Issue forms initially apply `bug` or `enhancement`. During triage, the maintainer may add one
priority (`P0` data/security risk, `P1` radio unavailable or unsafe, `P2` normal defect, `P3`
polish/documentation) and one area label when those labels exist in the repository. Security or
credential exposure is reported privately through GitHub Security Advisories, never as a public
Issue. Before coding, link the Issue to the relevant acceptance row in `TEST_MATRIX.md` or open a
new row. Hardware claims must cite a real trace; do not infer a key mapping from a key name alone.

Every fix should have a focused branch and PR that contains:

- the linked Issue and a concise behavior change;
- tests or a documented reason a test cannot be automated;
- updated `PROJECT_STATUS.md`, `WORK_LOG.md` or the relevant contract/profile document when behavior
  or evidence changes;
- `git diff --check`, secret review and the proportionate Android/Web verification commands from
  [DEVELOPMENT_RUNBOOK.md](DEVELOPMENT_RUNBOOK.md).

## Merge gate

Before merge, a reviewer verifies that the PR is scoped, the working tree does not include
`DEV_ENVIRONMENT_REQUIREMENTS.md`, no credentials/device secrets are staged, and the relevant checks
pass:

```powershell
Set-Location D:\mumla-dev
git diff --check
.\gradlew.bat :app:testFossDebugUnitTest :app:assembleFossDebug :app:assembleFossRelease --no-daemon
Set-Location D:\mumla-dev\web
pnpm test
pnpm exec tsc --noEmit
pnpm build
```

`.github/workflows/ci.yml` runs the equivalent Android and Web jobs on pull requests, pushes to
`main`/`agent/minimum-foundation`, and manual dispatch. The Android job also assembles the unsigned
FOSS release target so release-only Lint failures block merge, and uploads the commit-addressed FOSS
debug APK for 14 days. Required branch protection is a GitHub repository setting and must be
confirmed in GitHub; the existence of the workflow file alone does not make a check required.

Physical acceptance is required for changes to PTT, screen-off behavior, boot, reconnect timing,
location/APRS or model-specific provisioning. Record the result in `TEST_MATRIX.md` and
`PROJECT_STATUS.md` before marking the PR ready. Web portal changes also require a production smoke
check without exposing credentials.

## APK release gate

The repository is not release-ready merely because FOSS debug tests/build pass. A release candidate
must additionally have:

- a reviewed version name/code and release notes describing supported T99/T56 behavior and known
  limitations;
- a reproducible CI build from the exact commit, with the APK artifact retained by GitHub Actions;
- a release signing configuration kept outside Git, a published SHA-256 checksum and artifact
  provenance. Never use debug keys or commit signing files;
- the acceptance matrix reviewed for screen-off PTT, network-loss TX release, boot/reconnect,
  profile-specific key mappings, Config fetch/rollback and T56 APRS policy;
- an explicit decision on remaining open items (for example A-GPS attribution or T56 physical
  retest), rather than silently presenting them as passed;
- a GitHub Release attached to the tagged commit, not an APK copied from an arbitrary workstation.

Until these conditions are met, publish test APKs only as clearly labelled CI artifacts or draft
releases. The public Pages backend must remain free of server passwords, protected access tokens,
APRS passcodes and cached positions.

The manual `.github/workflows/release-apk.yml` workflow checks out an existing numeric `x.y.z...`
tag, requires the reviewed Android `versionCode`, builds `:app:assembleFossRelease`, verifies the
package/version/signature, creates SHA-256 files and publishes the tagged GitHub Release. The same
release also contains `minimum-provisioning-<tag>.zip`, a standalone Windows bundle with the signed
APK, provisioning/updater launchers, guarded T99/T56 scripts including the cellular migration,
prebuilt temporary Wi-Fi helper, updater README and cellular-policy README. The manifest and
workflow share an exact reviewed file allowlist. The bundle uses the included APK/helper and does
not require a source checkout or Gradle on the field workstation. Published checksum entries bind
only their exact asset basenames, so standard checksum tools do not depend on a CI runner path. Its
protected `release` environment must provide:

- `MINIMUM_RELEASE_KEYSTORE_BASE64`
- `MINIMUM_RELEASE_STORE_PASSWORD`
- `MINIMUM_RELEASE_KEY_ALIAS`
- `MINIMUM_RELEASE_KEY_PASSWORD`

The same environment must define repository/environment variable `MINIMUM_RELEASE_APPLICATION_ID`
with the reviewed package ID. The workflow rejects an APK whose package ID, `versionCode` or
`versionName` differs from the approved inputs.

These secrets and the signing certificate continuity must be configured and verified in GitHub
before the first run. As of this review, merge preparation exists, but release readiness remains
**NO** until CI passes on the integrated commit, PR review/approval is complete, release signing is
configured, the open hardware limitations are explicitly accepted, and the tagged workflow passes.
