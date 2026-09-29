# Implementation status (index)

This file remains as a compatibility link for earlier notes. The maintained hand-off document is
[PROJECT_STATUS.md](PROJECT_STATUS.md).

## Production admin portal status (2026-08-09)

- `web/` contains the Next.js admin portal and is deployed at `https://minimum.vra.or.th/` through
  Vercel's Next.js framework preset.
- Production persistence is Cloudflare KV over its REST API. The deployment requires the account,
  API-token and namespace environment variables plus a 32-character-or-longer session secret; no
  secret is stored in this repository.
- The first-run administrator handoff, pending-device queue, device CRUD, structured Schema-3 form,
  canonical per-model templates and automatic config-version advancement are implemented. Raw JSON
  is an Advanced fallback. Android reads the registered profile by Device ID; there is no handset
  token-copy or rotation step.
- The **Channels & default** editor exposes `radio.defaultChannel` through a highlighted selector
  and per-channel **Set default** action while keeping Last Selected Channel as handset-owned state.
- Verification completed: the web test suite, TypeScript and the production Next.js build pass from
  `D:\mumla-dev\web`. Browser QA covered a two-server/two-channel save and version advance from v7
  to v8 plus a 390 px viewport without page-level horizontal overflow. The operator reported that
  production administrator setup and registration of the development T56/T99 records are complete.

Use these documents by purpose:

- Current truth and pending work: [PROJECT_STATUS.md](PROJECT_STATUS.md)
- Code/data flow: [ARCHITECTURE.md](ARCHITECTURE.md)
- Build, ADB, boot and T56 procedure: [DEVELOPMENT_RUNBOOK.md](DEVELOPMENT_RUNBOOK.md)
- GitHub Pages and Android config behavior: [CONFIG_BACKEND.md](CONFIG_BACKEND.md)
- Done/open acceptance checks: [TEST_MATRIX.md](TEST_MATRIX.md)
- Reasons behind safety and scope choices: [DECISIONS.md](DECISIONS.md)
- Chronological milestone notes: [WORK_LOG.md](WORK_LOG.md)
- APRS tracking contract and verification: [APRS_TRACKING.md](APRS_TRACKING.md)
- GitHub Issue triage, merge and APK release gates:
  [GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md)

Hardware source documents:

- [T99 device profile](T99_DEVICE_PROFILE.md)
- [T56 device profile](T56_DEVICE_PROFILE.md)
