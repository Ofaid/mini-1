# Contributing to Minimum

Minimum is a managed-radio Android application with hardware-specific PTT, remote configuration and
public APRS behavior. Start with [the project status](docs/PROJECT_STATUS.md), then follow the
[GitHub issue, merge and APK release workflow](docs/GITHUB_RELEASE_WORKFLOW.md).

Use the GitHub Issue forms for bugs, device acceptance results and feature requests. Do not publish
server passwords, channel access tokens, device bearer tokens, APRS passcodes, signing material,
certificate fingerprints, exact private coordinates or unsanitized device logs. Report security
issues privately through GitHub Security Advisories.

Keep each change focused and link its Issue. Hardware, PTT, boot, reconnect, provisioning and APRS
claims require real-device evidence. Pull requests must preserve TX release safety, reconnect
throttling, Last Known Good configuration fallback and Last Selected Channel restoration.

Before opening a pull request, run the commands in
[the development runbook](docs/DEVELOPMENT_RUNBOOK.md) and update the relevant status, decision,
device-profile or acceptance document. Never stage `DEV_ENVIRONMENT_REQUIREMENTS.md`.
