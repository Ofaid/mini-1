# Work log

This short log records meaningful project milestones. Detailed code truth remains in
`PROJECT_STATUS.md` and the source files.

## 2026-08-24 - Correctness, teardown, provisioning and portal admission hardening

- Wired validated `ptt.maximumTxSeconds` into the service-owned watchdog for hold and toggle PTT,
  with per-transmission policy capture and fail-safe release on live policy changes. The accepted
  exported TALK interface now enters through the same readiness/release gates and cannot bypass or
  extend that watchdog.
- Added generation-gated APRS callbacks so stop/reconfiguration invalidates stale transport results,
  plus best-effort Mumla/Humla audio, SCO, connection and wake-lock teardown that cannot be aborted by
  one resource failure.
- Resolved the requested Android correctness-lint categories and documented the dedicated-device
  exported service/receiver design without changing that deployment behavior. Unrelated legacy Lint
  findings remain visible rather than being hidden behind a baseline.
- Made fresh Release provisioning verify the manifest-bound APK identity and signer before ADB and
  repeat the complete binding immediately before installation, so post-preflight replacement fails
  closed. The signed-release workflow now authorizes only a tag whose commit exactly equals current
  reviewed `origin/main`, before tag code or signing secrets are used. Added CI/release coverage for
  both gates and documented the separately published ZIP checksum as the pre-extraction trust anchor.
- Implemented staged distributed login admission with Cloudflare D1 atomic buckets, HMAC-only keys,
  bounded configured/decoy account state, expiry triggers and fail-closed Vercel behavior. Production
  activation remains pending server-side secret provisioning and deployment smoke acceptance.

## 2026-08-12 - T56 update provisioning and RYKS location recheck

- Rebuilt the FOSS debug APK from current `main`, then updated the connected T56 with
  `adb install -r` without uninstalling, clearing app data, rebooting, changing network settings or
  transmitting PTT. The original install lineage remained intact and the new APK returned to
  RadioShell Ready with its foreground service running.
- The protected status report showed the current portal config active, no pending candidate and a
  successful refresh immediately after the update. A sanitized comparison against the production
  endpoint matched schema, config version, channel count and default-channel presence.
- Repeated the redacted RYKS location probe for 120 seconds. GPS was enabled and reported 16
  almanac entries, but network provider availability was false and GPS produced zero ephemeris,
  SNR, used-in-fix or coordinates. This supports keeping RYKS APRS disabled, but does not prove
  permanent hardware incapability without a controlled open-sky test.

## 2026-08-12 - RYKS display-off PTT delivery and channel badge

- Removed the redundant `CHANNEL` label from the yellow RadioShell badge while retaining its
  accessibility description, color and selected alias.
- Physical device evidence showed that RYKS emitted `com.zello.ptt.down/up` while the display was
  off, but Android 8.1 logged `Background execution not allowed` and suppressed the manifest receiver
  before application code ran. The foreground service now registers the profile-specific PTT receiver
  at runtime and delivers each edge through the existing no-queue readiness gate, release safety and
  watchdog. Static manifest PTT actions were removed to avoid duplicate delivery.
- RadioShell now recovers an already-active service TX state when Android resumes it after the
  screen-off DOWN, covering the observer edge that occurs before the Activity is visible.
- Installed the reviewed build, visually confirmed the alias-only yellow badge and Ready state,
  then verified a physical hold from explicit `Asleep` / display OFF produced DOWN and UP about
  3.9 seconds apart and woke RadioShell. After the runtime-receiver correction, a second physical
  display-off hold transmitted successfully; Android recorded single-digit-millisecond delivery and
  RadioShell returned to Ready after release without stuck TX.

## 2026-08-12 - Tokenless Device Profile provisioning

- Removed the manual device bearer-token lifecycle from Android, the provisioning script, Web API
  and Admin UI. Creating a Device Profile for the six-character Device ID is now the only portal
  enrollment step.
- Device config endpoints now return a registered profile by Device ID and record unknown IDs in
  the bounded pending queue with `404`. Existing KV token fields are deleted on read and Android
  deletes its retired app-private credential file on upgrade.
- Android now refreshes at every process start in addition to the six-hour and network-return
  triggers, so reboot and OTA fetch current server configuration without rotating credentials or
  physically reprovisioning the radio.
- Made repeated model preparation tolerate Zello already being absent instead of treating the
  expected `pm path` miss as an ADB failure.
- Deployed the production endpoint, installed the tokenless APK with `adb install -r`, observed
  portal Config v12 become active/Ready, then rebooted the physical RYKS. Saved lab Wi-Fi,
  High Accuracy mode, RadioShell foreground, v12, `pending=false` and the Ready marker all survived;
  the retired token file was absent. The RYKS OEM install-policy property reset after reboot as
  expected and is unrelated to ongoing server configuration refresh.

## 2026-08-12 - RYKS Android and Web profile

- Fixed production Admin Save Configuration BotID rejection. The client route list incorrectly used
  Next.js `:deviceId` syntax, which BotID does not match, so PATCH requests lacked verification
  headers. Moved initialization to Next.js 15.5 `instrumentation-client.ts`, changed dynamic paths
  to BotID `*` wildcards, retained server-side enforcement and added route regression tests.
- Captured the operator-reported RYKS controls and corrected the conservative front-key policy:
  three-line is F2/scan 60 and toggles Device ID on one press, green is DPAD_CENTER/scan 353, and
  red is native POWER/scan 116 with the Android Power off / Reboot menu intentionally retained.
  F8/scan 66 and F7/scan 65 are the two below-PTT previous/next room controls. Installed the corrected
  APK and observed repeated physical F2 DOWN/UP events enter the identity-toggle path while
  RadioShell remained foreground instead of opening Settings.
- Commissioned the connected `ELINK/ym_258` RYKS (Android 8.1/API 27, 160x128) without committing
  its serial or Minimum Device ID. Device-tree input inventory identified rotary volume scans
  115/114, `CHAT` scans 216/249 and F7 scan 65.
- Decompiled the device's own PhoneWindowManager path to verify vendor keyCode 285 and
  `ro.build.ptt_type=ANYPTT` broadcasts `com.zello.ptt.down/up`. Added a RYKS-gated receiver path
  into the existing service PTT safety gate and kept both CHAT scans as PTT because the broadcast
  carries no scan-code discriminator.
- Added RYKS detection/defaults, F8/F7 room selection, front-key policy, app-private diagnostics,
  tests, Web model/template/schema data, a guarded model wrapper and one-shot/release-bundle support.
- Verified the ELINK PackageManager's per-boot `ro.build.install=1` gate, installed the debug APK,
  granted microphone permission, removed Zello for user 0, requested the recovery shortcut and
  passed the no-HOME-chooser check. Injected vendor keyCode 285 produced OEM DOWN/UP broadcasts and
  left Minimum foreground without a crash; physical labelled-key and display-off acceptance remain
  explicitly pending.
- Full Android unit tests and FOSS debug APK assembly passed. Portal tests, TypeScript and the
  optimized Next.js production build also passed.

## 2026-08-09 - Default channel editor and hand-off documentation

- The Web portal's **Channels & default** tab now exposes `radio.defaultChannel` as a dedicated
  selector, marks the selected channel with a **Default** badge and provides a **Set default** action
  on each channel card. The value is persisted and advances `configVersion`; it remains a fallback
  behind the handset's Last Selected Channel.
- Reconciled the architecture, configuration, project-status, acceptance and gap-analysis documents
  with the production Vercel/Cloudflare control plane and direct managed Android endpoint.
- Added `GITHUB_RELEASE_WORKFLOW.md` so field Issues, triage, PR merge review and signed APK release
  decisions have a repeatable public hand-off.
- Added structured Bug, Device acceptance and Feature request forms, a PR safety checklist,
  Android/Web CI and a manual tag-based signed FOSS APK workflow with signature/version/checksum
  verification. Release signing secrets and an actual tagged run remain release gates, not assumed
  completions.
- Release assembly initially exposed invalid `noBackup` domains in Android backup-rule XML. Those
  redundant entries were removed because `getNoBackupFilesDir()` is already excluded by Android;
  FOSS unit tests, debug APK and release APK assembly then passed together. CI now runs the same
  release target so a release-only Lint regression blocks merge.
- GitHub Actions run `31306714812` then passed on integrated commit `6ee5c5e6`. Both the Android job
  (unit tests, debug APK and unsigned release assembly) and Portal job (tests, TypeScript and
  production build) completed successfully. Signing and physical acceptance remain separate gates.

## 2026-08-09 - Managed credential provisioning and v1101 physical acceptance

- Reissued the one-time device bearer credentials from the production portal and installed them
  through the DUMP-protected provisioning receiver on T99 `GYZ3DE` and T56 `P1L4A0`. Temporary
  host and `/data/local/tmp` credential files were removed after installation.
- Physical refresh exposed a portal repair bug: deep-merging the baseline connection map added the
  unused `public-main` placeholder to otherwise complete records. Android correctly rejected that
  same-version content change. Connection maps now replace the template collection when supplied,
  with regression coverage preventing placeholder reintroduction.
- Removed the stale placeholder from both production records, which advanced them to config v1101.
  The endpoint now returns only `tse-public-main` and `e2hub`; T99 tracking remains disabled, while
  T56 tracking/APRS and Object name `HS3HP` remain enabled.
- Built and installed APK `9cda090-debug` on both radios. T99's active cache is v1101; both physical
  UIs are Ready on the restored `E2HUB HS1AB` selection. T56 continues to report accepted stationary
  GPS fixes. The isolated T99 restart used the persisted reconnect floor, and T56 recovered from one
  transport error after about 17 seconds rather than reconnecting rapidly.
- BotID enforcement was disabled only for the credential-rotation window because automated Chrome
  was classified as non-human, then restored to `BOTID_ENFORCE=true` with a Ready production
  deployment. Web regression verification is 17 tests plus TypeScript and production build.

## 2026-08-09 - Direct managed config refresh on API 22

- Live T99/T56 testing after a PC restart showed that the old Android trust store rejected the
  production Config endpoint, and that the client still required obsolete GitHub Pages
  `default.json`/model files before requesting its private record.
- Added a Config-only TLS socket factory that combines platform trust with bundled ISRG Root X1,
  enables TLS 1.2 and retains normal hostname verification. Trust setup fails closed.
- Managed devices now download the complete Schema-3 record directly from
  `/api/device-config/{deviceId}`. The embedded active/previous config remains the Last Known Good
  fallback; the public backend remains a non-secret reference artifact.
- Live verification reached the private endpoint on both API-22 radios without
  `SSLHandshakeException` or public-file `NotFoundException`. The expected remaining response was
  generic device-config unavailable because the prior one-time tokens had been rotated and still
  needed reprovisioning.

## 2026-08-09 - Production admin portal handoff

- Documented the deployed `web/` Next.js portal at `https://minimum.vra.or.th/`, using Vercel's
  Next.js framework preset and Cloudflare KV REST storage. Production requires server-only KV and
  HMAC/session secrets; no values were added to Git.
- Recorded the first-run handoff: create the administrator in the portal, register a device, copy
  its one-time bearer token into device-private provisioning, and rotate immediately if it is lost.
  Credential material is persisted only as scrypt/password and device-token hashes; device metadata
  and schema-3 config remain the stored device record.
- Verified the live custom domain and security boundary without credentials: `/api/session` returned
  `200` with `configured:false`; `/api/device-config/{deviceId}` and the legacy
  `/api/device/{deviceId}/config` returned generic `401 Unauthorized`.
- Verified the web project locally from `D:\mumla-dev\web`: `pnpm test` (8 tests),
  `pnpm exec tsc --noEmit`, and `pnpm build` all passed. The live admin/device handoff remains
  intentionally pending.

## 2026-08-08 - APRS Object identity, health and live receipt

- Added optional `channels[].alias` (1-32 visible characters) without changing schema version.
  RadioShell displays it as a large amber `CHANNEL` badge that remains visually separate from the
  active-talker area; full Mumble paths remain connection-only and are no longer written to the UI
  after join. Configs without an alias remain compatible through the existing `label` fallback.
  The public config family advanced to config version 6 for this effective default change.
- Extended schema 3 with optional `tracking.aprs.objectName`. Safe ASCII labels of 1-9 characters
  are uppercased and padded to APRS' fixed Object field; omission remains backward compatible and
  derives `VR-<DeviceID>`. Runtime identity changes clear duplicate/in-flight state so the next
  accepted fix is published under the new name. The current implementation does not send a kill
  packet for the old Object name, so its APRS.fi history may remain visible.
- Added a DUMP-protected in-app update path and guarded T56 PowerShell command that change only the
  Object label, advance `configVersion` and preserve credentials without exporting the private
  active config from firmware that lacks `run-as`.
- Provisioned the connected UNIPRO/ZX T56 (ADB serial `81e36aae`) with `tracking.aprs.objectName`
  set to `HS3HP`; the update was applied in place, the app was restarted once to load the new
  tracking identity, and RadioShell was reopened. This assignment
  is device-local and intentionally is not copied into the public backend device manifest.
- Replaced the temporary callsign-owned APRS position experiment with the final APRS Object
  contract. The default T56 identity is an Object (`;`) named `VR-` plus the six-character Minimum
  Device ID, exactly nine characters, so APRS.fi wildcard search can use `VR-*`.
- Added state-specific primary-table symbols: house/home (`-`) while stationary, person/jogger (`[`)
  while walking, and car (`>`) while in a vehicle. The symbol is part of the wire contract and is
  selected by `AprsTrackingManager`, not by the UI.
- Added a compact Health comment to the existing position beacon: fix accuracy, battery percent and
  charging/full state, battery temperature, Wi-Fi RSSI, mobile type/RSSI when available, and free
  app-volume storage. Unavailable radio values are `NA`; SSID, IMEI, phone number, serial, Device
  ID, credentials, tokens and duplicate coordinates are excluded.
- Confirmed the production send-only path requires HTTPS `204` plus `X-Packetsrcvd > 0`; only then
  does the app persist the last successful fix. API-22 needed the pinned ISRG Root X1 trust anchor.
- Removed `requestSingleUpdate()` after the T56 firmware failed to deliver its callback; stationary
  acquisition now uses regular GPS updates for a bounded 90-second window and polls every 30 minutes.
- Hardened beacon cadence and duplicate handling: no mobile interval below 60 seconds, one in-flight
  plus one newest pending beacon, and long backoff after post-write receipt uncertainty.
- Live open-sky T56 acceptance produced a GPS fix, a positive APRS-IS receipt and an APRS.fi-indexed
  `VR-` Object with Health fields. T99 remained isolated with no tracking manager/location request.
- An earlier callsign-owned packet may remain in APRS.fi history because APRS-IS does not delete
  accepted packets. The Object is the only current source of truth.
- Documentation source of truth is now `docs/APRS_TRACKING.md`; the full JVM suite is now 93 tests
  passing and the FOSS debug APK build succeeds from `D:\mumla-dev`.

## 2026-08-07 — T56 profile and simultaneous talker UI

- Completed T56 PC commissioning: side keys delivered Android keyCode 260/scan 138 and
  keyCode 266/scan 59; physical screen-power OFF -> ON behavior was observed; screen-off PTT
  delivered keyCode 261/scan 216 and Minimum intentionally woke its status surface. Android Power
  mapping remains open because the observed scan 63/keyCode 264 is also the one-person-key path.
- Installed a private schema-3 T56 config cloned from the existing private endpoint set, changing
  only the T56 Device ID and requested usernames; secrets were never printed or committed.
- Removed the visible service-name and inline Device ID text without collapsing their layout rows.
  A one-second hold on T99 green or T56 one-person now toggles a full-screen Device ID overlay;
  T99 green short press retains its existing room-confirm behavior.
- Enabled high-accuracy Location on T99 and device-only GPS on T56, and added a temporary redacted
  location probe. T56 passed a real GPS fix at about 5 m accuracy with 13 satellites used; T99 repeated its
  zero-SNR/no-fix result. Qualcomm A-GPS flags exist, but assisted operation remains unaccepted.
- Corrected the T56 network-location conclusion after confirming Google's consent activity was
  launched and then covered by Minimum. Added an explicit operator consent wait with GPS-only
  rollback, plus a deny-by-default tracking capability that accepts T56 and rejects T99/generic
  hardware until each model has real-device location evidence.
- Decompiled the T56 Google Services Framework path with the firmware's own `oatdump`, identified
  the supported `disable` extra and `use_location_for_services` state, and physically completed a
  positive consent run. T56 finished at high-accuracy `gps,network` and Minimum was restored;
  provisioning now verifies stored consent before enabling that mode.
- A redacted 30-second post-consent probe returned both a roughly 13 m GPS fix and a roughly
  29.21 m network fix on T56. Network location is accepted; A-GPS remains open because the probe
  cannot attribute the GPS fix to SUPL/XTRA assistance.

- Replaced the provisional model name with the verified T56 profile after capturing the connected
  `UNIPRO/ZX/L809` Android 5.1.1 radio. The public profile records no unique serial or secret.
- Captured the physical navigation, person, side, PTT and volume-knob inputs. T56 PTT is vendor
  keyCode 261; F1 is its Menu key and is explicitly excluded from PTT.
- Added a T56 provisioning wrapper that auto-detects exactly one authorized `UNIPRO/ZX` device and
  delegates to the shared guarded provisioning flow with manufacturer/model verification.
- Provisioned the connected T56 with the guarded flow: removed Zello for user 0, created its Minimum
  Device ID, installed the Launcher3 recovery shortcut, verified no HOME chooser, imported the
  non-secret embedded config through a protected receiver fallback (the firmware has no
  `/system/bin/run-as`), and confirmed a real reboot returns to RadioShell.
- Changed RX rendering to retain every simultaneous remote talker. Compact T99 displays reserve
  two lines, larger displays reserve four, and overflow replaces the final visible line with `+N`
  while accessibility retains the full ordered list.

## 2026-08-05 — PTT recovery and deliberate hardware actions

- Added fail-safe PTT recovery from MinimumHome and disconnected RadioShell: alert, foreground
  RadioShell and request connection immediately, but never queue the triggering press for TX.
- Added a service-owned room-ready gate so no Activity or MediaSession PTT path can transmit before
  the configured room join has been verified.
- Added a five-second hold requirement for T99 MENU/EXIT/red and a one-second Up/Down hold that
  selects and joins the configured room without green confirmation.
- Installed and checked on T99 without injecting PTT: short exit keys stayed in RadioShell, long F2
  opened MinimumHome, green reopened RadioShell, long Up returned Ready, and a stopped-process
  recovery intent returned Ready in one second.
- Recorded the standard Android limitation: F1 cannot be captured globally while an unrelated app
  owns the foreground; a global route needs separately verified OEM/privileged/keylayout support.
- Physical dashboard-F1 acceptance found a cross-window race: the old press reached RadioShell and
  started TX. Added a pre-launch service safety action plus Activity/service release-required state,
  rebuilt and installed the hotfix, and left the physical regression honestly open.
- Corrected an earlier red-button misidentification with an isolated physical capture: the T99
  kernel reports matrix-keypad scan 2 / `KEY_BACK`, while its vendor WindowManager remaps the event
  to Android `KEYCODE_DPAD_RIGHT` (22). Added DPAD_RIGHT to the protected five-second exit and
  diagnostic paths while retaining BACK for firmware compatibility.
- Added release-timestamp completion so UI-handler delay cannot cancel a completed hold, and moved
  protected-exit interception ahead of normal Activity dispatch so repeated vendor key events stay
  inside the deliberate-action path. Physical acceptance then passed: the prompt appeared and a
  greater-than-five-second red hold opened MinimumHome.
- Disabled the normal Mumla PTT confirmation click for every managed radio in defaults and runtime;
  verified the installed T99 preference is false while retaining the PTT failure alert.
- Split managed identity roles: config schema 2 now requires an explicit Mumble username instead
  of reusing Device ID. Provisioning treats the six-character Device ID as the Config Profile used
  for device-specific lookup. Current T99 values are profile `GYZ3DE` and Mumble username
  `E25FGL-T99`; operator account `GY3ZDE` remains separate.
- Installed private config v1003 on T99, verified the app preference and active config both use
  `GYZ3DE`, and observed a fresh schema-2 launch return to the exact configured room in Ready state.
  The temporary workstation migration copy (which could contain room access data) was removed.
- Fixed the RX UI race caused by Humla's unordered observer fan-out. Activity rendering and config
  idle evaluation are now deferred/coalesced until `MumlaService` has applied the same talk-state
  event to `RadioReceiveTracker`; room-join completion now uses the same RX-aware path. Added pure
  Ready/single/multiple-state tests, built and installed the APK. The operator then confirmed a
  natural live Ready/speaker/Ready cycle and that both reported visual defects are gone.
- Probed T99 location independently of Minimum. Android exposed its Qualcomm GPS stack and almanac
  entries, but two sessions totalling more than five minutes produced zero SNR, no ephemeris, no
  used satellites and no GPS fix. Location remains a failed/open hardware acceptance item and was
  not added to provisioning defaults.
- Added secure lab Wi-Fi provisioning to `prepare-t99.ps1` using a temporary standalone Android
  5.1 helper and a git-ignored DPAPI credential. The helper never logs the PSK and is uninstalled
  after use. Profile refresh passed on T99, followed by automatic reconnection to the lab SSID in
  approximately 5.3 seconds after Wi-Fi was disabled and re-enabled.

## 2026-08-04

- Confirmed the working tree and GitHub draft PR #1 for `awatchar/minimum`.
- Added automatic radio PTT defaults and a central multi-key manager for F1/F2 plus media/headset
  keys. F1 was verified from the T99 `gpio-keys` raw event; F2 remains a live verification item.
- Added application-start Device ID creation.
- Added `RadioConfigRepository` with embedded default, HTTPS fetch, merge, validation, cache and
  rollback copy.
- Added the non-blocking six-hour `RadioConfigUpdater` startup refresh.
- Added the non-launcher `RadioShellActivity` with Device ID/profile display and touch PTT bridge.
- Added the hand-off, architecture, runbook, backend contract, test matrix and decision records.
- FOSS debug unit tests and APK build passed after the code changes.
- Applied the user-provided Minimum logo to the Android adaptive icon and added an API 21+
  legacy launcher resource for T99. Changed the launcher label to `Minimum` and retained the
  editable SVG source at `docs/Minimum-app-icon-foreground.svg`.
- Added `MinimumHomeActivity` for T99/T56 with one large icon per swipe page for Minimum and
  Settings. A reboot test exposed an unacceptable Android HOME chooser while Launcher3 remained
  enabled. T99 rejected both a third-party HOME priority and package-disable takeover as reliable
  production solutions. The final design removes Minimum from the HOME resolver, launches the radio
  dashboard explicitly at boot, installs a Minimum recovery shortcut in Launcher3, and verifies no
  ResolverActivity. Reboot and shortcut recovery passed on the physical T99.
- Added physical-key dashboard navigation after the T99 proved non-touch in practice: up/left and
  down/right change pages, while Select/Enter/Call activates the visible page. PTT F1/F2 remain
  isolated from dashboard actions.
- Re-read the complete Technical Brief and added a phase/acceptance-test gap analysis instead of
  treating foundation classes as completed end-to-end features.
- Changed Minimum's unset/invalid theme default to dark while preserving explicit light/system
  choices, then visually verified the updated APK on T99.
- Added and reviewed the bounded Luna worker configuration outside the repository. Its first task
  produced `AccessTokenResolver`; Sol review corrected its test fixtures, added a test-only JSON
  runtime, and the full FOSS unit-test/APK build passed.
- Completed the config-driven radio path: typed validation, downgrade rejection, public-token
  authentication, automatic certificate/connect/reconnect, exact full-path room join, room preset
  navigation and the compact dark ready/RX/TX/access status UI.
- Added optional exact SHA-256 pinning for managed/self-signed Mumble servers. The first unpinned
  T99 connection correctly failed closed; the locally provisioned exact pin allowed the existing
  app-private BKS trust path to connect without disabling TLS verification.
- Extended `prepare-t99.ps1` to install a local config into app-private storage without showing its
  token, apply restrictive permissions and launch the managed radio client.
- Passed end-to-end physical T99 acceptance against the supplied active server: authentication,
  nested full-path room join, green ready state, connection persistence with the display off, and a
  real reboot returning directly to the ready room without a HOME chooser or touch input. No PTT
  transmission was made while the operator was away; F2 and screen-off hardware PTT remain open.
- The operator subsequently proved physical PTT while the T99 display was off. Code/device review
  found Minimum's active MediaSession/service path and no provisioning setting that enables it, so
  no PTT system-setting command was added to `prepare-t99.ps1`. Exact key event identity remains to
  be captured before labelling the path as KEY_MEDIA versus raw F1/F2.
- Replaced pin-required self-signed TLS policy with config-authorized automatic trust by default.
  Normal Android trust still runs first, app-private trust is used for the retry, and an optional
  configured SHA-256 pin remains a stricter fail-closed override.
- Live-tested the new no-pin path by deleting only Minimum's app-private Mumble trust store and
  provisioning a config with automatic trust enabled. T99 recreated the store, reconnected and
  returned to the exact ready room without an operator dialog.
- Implemented the Technical Brief's Last Known Good config lifecycle: network-return refresh,
  non-overlapping fetches, pending staging, RX/TX/transition idle gate, in-memory candidate trial,
  commit only after the configured room is joined, failure fallback and explicit previous rollback.
  RX safety state is now owned by the long-lived service and covers talk, shout and whisper events.
  Repository/updater/policy JVM tests and the FOSS APK build pass. A physical T99 acceptance attempt
  left active v1001 untouched and v1002 safely pending when the workstation ADB host wedged; no
  candidate was falsely marked active. The final APK passed build but remains to be installed after
  USB/ADB is reconnected.
- Hardened the managed-radio lifecycle: retry every unexpected disconnect indefinitely with capped
  exponential backoff, immediate network-return retry plus timer fallback, and service intent
  redelivery after process death. Certificate-policy mismatch remains a fail-closed hold.
- Rebuilt RadioShell as a compact full-screen state surface for the real 132x132 T99 constraint:
  no touch PTT, no false Ready before room join, service-owned speaker names, prominent RX/TX/error
  screens, connection attempt count and elapsed TX timer.
- Enabled radio preprocessing, half-duplex, TTS, PTT confirmation and auto-reconnect automatically;
  corrected the Speex probability setter, half-duplex runtime mode lookup and teardown unmute.
  Thai TTS is selected conditionally on installed engine support.
- Added bounded wake-on-RX/TX/disconnect and a local PTT failure tone/state when offline or no encoded
  packet reaches the synchronized connection. Documented that this is not a server delivery ACK.
- Added `scripts/test-radio-reconnect.ps1` and `docs/RECONNECT_TEST_PLAN.md` for repeatable network,
  server, process, certificate and supervised PTT-failure acceptance. Physical execution remains
  blocked by the workstation ADB host wedge.
- Retried device acceptance after a full T99 power cycle. Windows continued to report the composite
  device and MI_03 ADB Interface as healthy, but ADB daemons from platform-tools 36.0.0 and an
  isolated official 34.0.5 both hung. This isolates the remaining recovery action to a Windows
  USB-stack reboot; no handset reset, app-data clear, network outage or PTT transmission was made.
- Hardened reconnect acceptance before the next physical run: the script rejects ambiguous network
  state, reads both settings back after restoration, and uses a stable ASCII accessibility marker
  from RadioShell instead of localized Thai text. Windows PowerShell 5.1 parsing, JVM tests and the
  FOSS debug APK build pass.
- The first live 30-second network-loss run restored both transport settings and Minimum rejoined
  the configured room, but the harness could not see the Ready marker after the display timed out:
  T99/API-22 `uiautomator` exposes no app nodes while the display is off. The harness now wakes an
  off display only for UI inspection before retrying the marker check.
- After the Windows reboot restored ADB, installed the latest APK and physically verified config
  promotion: active v1002, previous v1001, no pending candidate and Ready in the configured room.
- Passed the guarded 30-second Wi-Fi/LTE-loss acceptance. Both original transport settings were
  restored and read back, then Minimum returned to the stable Ready marker without PTT.
- A baseline same-UID process kill exposed T99's OEM behavior: ActivityManager scheduled service
  redelivery roughly 16 minutes later. Added a profile-gated renewable AlarmManager watchdog lease
  (10-second heartbeat, 30-second expiry) that retries RadioShell startup independently of the dead
  process. The final physical SIGKILL test produced a new PID in 23.9 seconds and Ready in 30.9
  seconds without force-stop, manual post-kill launch, config mutation or PTT.
- Captured all ten physical T99 buttons at the Linux input layer and classified them with the
  installed Android keylayout. Crucially, labelled PTT is gpio-keys F1 while labelled EXIT is F2;
  the prior provisional F1/F2 PTT default could therefore turn EXIT into a short TX.
- Captured the real PTT at Android level as keyCode 131, scanCode 59, deviceId 4, source 0x101,
  `gpio-keys`, including repeat and UP. T99 now forces F1, rejects F2 before stale settings, routes
  MENU/EXIT/green/red according to their physically captured events, and keeps media keys only as
  alternate PTT inputs. Added a bounded app-private hardware-key diagnostic log with no text,
  config, token or audio content.
- Verified Mumble's upstream IP autoban defaults and hardened every managed-radio connection path:
  transport-only 15/30/60-second retry, a persisted 15-second global attempt guard, and fail-closed
  handling for reject/kick/ban/auth errors. Routine server chat remains logged and available to TTS
  but no longer creates Mumla's priority-high heads-up notification on T99/T56. All 42 JVM tests and
  the FOSS debug APK build pass. Windows currently sees the T99 WinUSB ADB interface but ADB does
  not enumerate serial `12344321`, so installation and live notification/reconnect timing remain
  pending; restarting the exact device interface was denied without administrator access.

## Prior milestones

- Integrated the guarded T56 cellular policy and secure existing-device updater on versionCode
  `3070301` (`3.7.3-minimum.2` tag contract). Review hardening added verify-only reboot persistence,
  truthful cellular WARN propagation, non-secret APN projection, no unverified modem-mode writes,
  immutable T56 hardware gates, full `apksigner` verification, read-only preservation snapshots,
  identity-correlated reboot recovery, sanitized failure recovery and an exact release-bundle
  allowlist. Physical updater acceptance and publication remain deliberately open.

- T99 hardware/ADB/input investigation and sanitized profile documentation.
- MediaSession PTT bridge and 120-second fail-safe watchdog.
- Automatic certificate generation on first run.
- Boot auto-start and simulated T99 boot verification.
- Zello user-0 removal and guarded repeatable removal script.
- Static GitHub Pages configuration backend and workflow.
