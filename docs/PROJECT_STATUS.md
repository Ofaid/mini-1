# Minimum project status (source of truth)

Last reviewed: 2026-08-24

This is the canonical hand-off document for the `awatchar/minimum` private-lab deployment. If another
document disagrees with this file, verify the code and update this file first.

## Repository and build identity

- Local repository: `D:\VR Android App\mumla`
- Build-safe junction: `D:\mumla-dev` (same working tree; use this path for Gradle/NDK)
- GitHub remote: `https://github.com/awatchar/minimum.git`
- GitLab upstream remote: `https://gitlab.com/quite/mumla.git`
- Humla upstream history is retained in the submodule; Minimum's required Humla commit is published
  as branch `humla-minimum` in the same GitHub repository and `.gitmodules` points there.
- Integration base: `main`
- Active correctness/security batch: `fix/lab-correctness-batch` ([PR #26](https://github.com/awatchar/minimum/pull/26))
- Android application ID: `se.lublin.mumla`
- The integrated Issue #11/#12 release candidate uses versionCode `3070301`; the compatible next
  Git-derived release tag/versionName is `3.7.3-minimum.2`. This is a preliminary integration
  candidate only: it has not been tagged, published or accepted on hardware.
- The existing-device updater now fail-closes on exact bundle/APK identity, requires Android Build
  Tools `apksigner` for full signature verification and exactly one reviewed signer, preserves Device ID, selected channel, active
  Last Known Good config digest and managed safe-settings digest, and identity-correlates a device
  after reboot. The one-time legacy `3070300` bridge first tries a non-creating `run-as` read of the
  existing app-private public ID. Its non-debuggable fallback requires the existing RadioShell to
  already be focused and package-bound Ready in a fresh UI hierarchy; the updater never starts it,
  persists raw UI XML or calls a receiver without that proof. It then records newly available expanded fields as bootstrapped rather than
  falsely preserved. The versioned `CELLULAR_POLICY_V1_T56` migration applies only from versionCode
  `3070300` or older to `3070301`, is T56-only/reversible, and requires post-reboot verify-only
  evidence. Cellular WARN remains overall WARN; no modem-mode mutation or cellular-ready PASS is
  claimed from a Settings database readback.
- Current supported build target: FOSS debug APK
- Local FOSS release assembly and release Lint now pass, but the output is not an approved public
  release until the application ID/signing identity, protected GitHub environment, tagged workflow
  and remaining hardware limitations are accepted.
- GitHub now has structured Bug/Device acceptance/Feature request forms, a PR safety checklist,
  Android/Web CI and a manual signed-release workflow. CI run `31306714812` passed on integrated
  commit `6ee5c5e6`: portal tests/type-check/production build and Android unit tests/debug APK/
  unsigned release assembly all completed successfully. This proves the integrated build, but not
  public release readiness until required checks, signing identity/secrets and remaining hardware
  limitations are accepted. See [GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md).

## Production admin portal status

- The private configuration portal is implemented in `web/` as a Next.js application and deployed
  at `https://minimum.vra.or.th/` with Vercel's **Next.js framework preset**.
- Production device/admin data is stored in Cloudflare KV through the server-only REST adapter.
  `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_KV_NAMESPACE_ID`,
  `SESSION_SECRET` is a deployment secret; the KV base URL is configurable for compatible test
  services.
- Distributed login admission is implemented for a separate Cloudflare D1 database and atomic
  fixed-window upserts; production activation is pending Vercel secret provisioning, deployment and
  smoke acceptance. The client gate precedes the KV lookup and account gate; nonmatching usernames
  share a bounded decoy account bucket, and expired rows are pruned by schema triggers. Identities
  are stored only as HMAC bucket digests. Production fails closed when D1 or its server-only
  configuration is unavailable; local development/tests retain a deterministic in-memory adapter.
- The portal provides a first-run administrator handoff, scrypt password hashing, an eight-hour
  HttpOnly admin session, pending-device registration, device CRUD, a structured Schema-3 editor,
  canonical model templates and automatic config-version advancement. The everyday editor has
  dedicated Server, Channel/Alias/Access, PTT behavior and T56-only Location/APRS sections; raw JSON
  is an Advanced fallback rather than the primary workflow.
  The **Channels & default** tab exposes `radio.defaultChannel` explicitly: an administrator can
  select it from the Default channel control or use **Set default** on a channel card. This value is
  the fallback only when no saved Last Selected Channel exists or the saved channel was removed.
  Device configuration delivery uses the registered six-character Device ID at
  `/api/device-config/{deviceId}`; `/api/device/{deviceId}/config` remains a compatibility route.
  There is no device bearer-token copy/rotation step. Existing KV token fields and Android token
  files are removed during migration. Admin reads and mutations remain session protected.
- Portal-issued config starts at v7 so it is newer than Android's bundled v6 baseline. Legacy portal
  records are repaired without dropping private connection/channel/APRS values. Connection maps
  supplied by a device record replace the template map, so repair cannot reintroduce the placeholder
  `public-main` server. T99/generic profiles cannot enable location tracking. The development T56
  and T99 records are registered and provisioned at config v1101. Local verification passes the web test suite, TypeScript,
  production build, two-server/two-channel browser round-trip, and 390 px responsive QA without
  page-level horizontal overflow.
- Production BotID coverage for dynamic admin mutations now initializes from Next.js 15.5
  `instrumentation-client.ts` and uses BotID `*` wildcard paths. Save Configuration, Delete and
  Delete receive verification headers while server-side enforcement remains enabled.

## Completed

- Imported the Mumla/Humla source and preserved the existing Mumble, TLS, Opus, audio and
  foreground-service core.
- Captured and documented T99 identity, USB, display, audio and input data.
- Added T99/T56/generic device profile detection and a central multi-key PTT manager. T99 physical
  capture proves PTT is `gpio-keys` F1 and EXIT is F2, so T99 now accepts only F1 plus media/headset
  alternates and forcibly resets its managed push-key preference to F1. T56 physical capture proves
  PTT is vendor keyCode 261 while F1 is Menu, so T56 accepts 261 plus media/headset alternates and
  explicitly rejects F1.
- Added the six-character persistent public Device ID and unit tests. It is now created at app
  process startup by `MumlaApplication`; provisioning may assign this lookup identity as the
  user-facing Config Profile without changing any USB serial.
- Added MediaSession handling for Android public media-style PTT keys.
- Added fail-safe PTT recovery. F1 received by the T99 recovery dashboard opens RadioShell, sounds
  a local failure alert and requests an immediate connection; a disconnected RadioShell does the
  same. The triggering press is never queued for later TX, so the operator must press again after
  Ready. A service-backed release-required lock is armed before the Activity transition and cleared
  only after key-up, preventing the original press from becoming a new RadioShell DOWN event.
- The service-owned PTT watchdog now uses validated `ptt.maximumTxSeconds` (1..120) for both
  hold-to-talk and toggle transitions, captures the active limit per transmission, and safely
  releases TX if policy changes mid-transmission. The accepted exported TALK control is routed
  through the same readiness/release gates and watchdog; repeated ON cannot extend an active
  deadline. Disconnect/service destroy and timeout still disarm callbacks and require a release
  edge before another TX.
- APRS transport results are generation-gated across stop and configuration changes. Stale
  success/failure callbacks cannot persist a receipt, mutate coordinator state or schedule retry;
  rejected/throwing transport paths release the in-flight logical attempt.
- Mumla/Humla teardown is best-effort and idempotent across connection, audio input/output/encoder,
  SCO, notifications and wake locks. Destroy state blocks deferred reconnects and late callbacks;
  each cleanup action still runs when an earlier resource reports a runtime failure.
- Added a service-owned managed-radio TX gate: synchronization, PTT mode and verified entry into the
  configured room must all be true before Activity or MediaSession input can start transmission.
- Made first-run client certificate creation automatic with retry on failure.
- Added boot auto-start, enabled by default, with an OEM/background-launch exception guard.
- Removed Zello for user 0 on the connected T99 and T56 and evolved the guarded PowerShell workflow
  into `scripts/prepare-t99.ps1` plus the T56 wrapper: serial report/collision handling, model
  verification, Zello removal and Minimum Device ID verification. The old Zello script name remains
  a compatibility wrapper.
- Added a public static GitHub Pages backend under `backend/`, with schema, defaults, model files and
  a Pages workflow. No user token is committed.
- Added Android-side `RadioConfigRepository`: embedded safe default, Device-ID-addressed complete
  device config from the Vercel control plane, validation, size limits and active/previous cache
  files. Managed refresh no longer depends on the GitHub Pages default/model files. API-22 devices
  combine platform trust with the bundled ISRG Root X1, force TLS 1.2, retain hostname verification
  and fail closed if trust initialization fails.
- Added `AccessTokenResolver` with JVM tests for per-channel public-token trimming, ordering,
  deduplication and safe exclusion of none/protected entries. The radio connection passes only the
  selected channel's values through Humla authentication without writing them to the server
  database, preferences or logs.
- Added a startup refresh, best-effort six-hour background refresh scheduler, network-return trigger
  and in-flight guard. Reboot and OTA process start therefore check the server immediately. Failed
  attempts do not postpone the next retry and never delay normal startup.
- Remote config now has an explicit Last Known Good lifecycle: validated downloads are staged as
  `pending-config.json`, trialled only while RX/TX and connection transitions are idle, promoted to
  active only after the candidate connects and joins its configured room, and discarded on trial
  failure. The old active config becomes `previous-config.json`; repository rollback and corrupt-
  active recovery are covered by JVM tests.
- RX idleness is service-owned and tracks all remote talking, shouting and whispering sessions, so
  resuming an Activity mid-transmission cannot incorrectly activate a pending config.
- Fixed ordering between Humla's unordered observer fan-out and the service-owned RX tracker.
  RadioShell now renders only after the tracker has applied the event, avoiding a Ready flash at RX
  start and returning to Ready after the final remote talker stops; the same deferred snapshot
  protects pending-config idle decisions.
- Managed-radio reconnect now retries transport failures indefinitely with capped 15/30/60-second
  backoff, a persisted 15-second minimum interval across every connection path, a 60-second OEM
  broadcast fallback and Android service-intent redelivery after process death. Server reject,
  kick, ban and other non-transport failures stop instead of repeatedly authenticating. This is
  aligned with the verified Mumble default autoban window of 10 attempts per 120 seconds. T99/T56
  also renew a process-independent 30-second AlarmManager lease every 10 seconds because T99
  firmware can defer a killed service restart for roughly 16 minutes. A certificate pin/policy
  failure remains an intentional fail-closed retry hold.
- Replaced the touch PTT screen with a compact hardware-first UI that keeps the Android status bar
  visible: whole-screen
  connecting/RX/TX/error states, speaker identity sourced from the long-lived service, room-join
  gating before Ready, connection attempt count and a live TX elapsed timer.
- RX state now retains the complete ordered set of simultaneous remote talkers. T99-class compact
  displays reserve two lines, larger displays reserve four, and overflow uses the final visible
  line for `+N` while accessibility retains the full list.
- RadioShell now renders an optional per-channel `alias` in a prominent amber badge without a
  redundant literal `CHANNEL` prefix,
  separate from the talker area. Room resolution still uses the full configured Mumble `path`;
  legacy configs fall back to `label`, and join/service refresh callbacks no longer expose the path.
- Managed radios retain server chat logging and TTS but suppress Mumla's priority-high/vibrating
  chat notification so routine server messages do not appear as transient heads-up alerts.
- Managed radios automatically enable PTT mode, input preprocessing, half-duplex playback muting,
  auto-reconnect and TTS, while suppressing the normal Mumla PTT confirmation click. The Speex VAD setter and half-duplex runtime/
  teardown unmute paths were corrected; Thai TTS is selected when the installed engine provides a
  `th-TH` voice.
- RX, TX and disconnect edges wake the small-radio display for a bounded five seconds. Offline or
  locally undeliverable PTT produces an error tone and full-screen failure state; encoded-packet
  confirmation is explicitly not claimed as remote server receipt.
- Added `MinimumHomeActivity` as the small-device recovery dashboard with one large icon per swipe
  page: Minimum and Settings. T99 F1 reopens the radio from this dashboard, and physical green
  activates the visible page. It is intentionally not an Android HOME handler because the T99 OEM
  resolver excludes it and displays an unusable chooser.
- Completed the dark `RadioShellActivity`: it loads the Last Known Good config, silently ensures a
  client certificate, connects/reconnects automatically, authenticates with resolved public room
  tokens, restores and resolves the selected channel by its exact full path, joins it, and displays offline,
  connecting, ready, RX, TX and access-denied states. Holding Up/Down for one second selects and
  joins the adjacent configured room without confirmation. MENU (`DPAD_CENTER`), EXIT (F2) and red
  (vendor-remapped `DPAD_RIGHT`) must be held for five seconds before opening the recovery dashboard; physical green
  (`KEY_MENU`) remains an immediate confirm/rejoin control.
- Added config-authorized automatic trust for managed/self-signed Mumble servers. Normal Android
  trust is attempted first; on failure, `autoTrustServerCertificate` defaults to true, stores the
  presented leaf certificate app-privately and retries without a dialog. An optional SHA-256 pin is
  stricter and a mismatch is still refused.
- Added config-version downgrade rejection and JVM tests for config parsing, downgrade behavior,
  token handling and full-path room resolution.
- Advanced the config contract to schema 3. Stable channels reference keyed connection profiles,
  allowing host/port/username/server-password/TLS policy and access tokens to differ per selection.
  The last selected channel ID is persisted app-privately and restored across Activity/process/
  connection recovery; switching auth context reconnects before joining. The current T99 uses
  Config Profile `GYZ3DE` and connection username `E25FGL-T99`.
- Minimum now defaults to dark mode while preserving an explicit user choice of light or system
  theme.
- Added a legacy Launcher3 recovery shortcut installer and provisioning receiver. Launcher3 now
  contains Minimum plus Settings, while provisioning verifies that system HOME has no chooser.
- Extended `scripts/prepare-t99.ps1` to install a locally supplied config into app-private storage,
  set restrictive permissions, grant/recognize microphone permission by Android API level and open
  the radio client without exposing token values.
- Extended T99 preparation with secure lab Wi-Fi provisioning. The public script contains only the
  SSID; its ignored password is a Windows-user-bound DPAPI credential. A temporary standalone
  helper saves/enables the WPA2 profile and is then removed. Physical refresh and Wi-Fi off/on
  auto-reconnection to the lab network passed.
- Captured the connected T56 as `UNIPRO/ZX/L809`, Android 5.1.1/API 22, 160x128, and documented its
  navigation, person, side, PTT and rotary inputs. Added `scripts/prepare-t56.ps1`, which selects an
  authorized `UNIPRO/ZX` target and delegates to the shared guarded provisioning implementation
  only after manufacturer/model verification. The T56 was provisioned on the connected target:
  Zello user-0 removal, Minimum Device ID creation, Launcher3 shortcut/HOME chooser check and
  private embedded-config import all passed. The APK was then reboot-tested; boot returned directly
  to RadioShell without a chooser.
- Commissioned the connected RYKS build identity as `ELINK/ym_258`, Android 8.1/API 27, 160x128.
  Added the managed `ryks` Android/Web profile, guarded one-shot/provisioning support and release-
  bundle inclusion. Kernel/device-tree evidence identifies rotary volume scans 115/114, two OEM
  `CHAT` PTT scans 216/249 and F7 scan 65. The modified PhoneWindowManager maps CHAT to vendor
  keyCode 285 and, with `ro.build.ptt_type=ANYPTT`, emits `com.zello.ptt.down/up`; Minimum now gates
  those RYKS-only actions into the service-owned PTT path. The debug APK was installed through the
  verified per-boot `ro.build.install=1` policy, Zello was removed for user 0, recovery/HOME checks
  passed and injected vendor-key DOWN/UP produced both OEM broadcasts without a crash.
- Deployed tokenless Device-ID config lookup and installed the matching APK on the physical RYKS.
  A fresh process downloaded portal Config v12, completed the pending Last Known Good trial and
  reached Ready. A real reboot then reconnected the saved lab Wi-Fi, preserved Android High
  Accuracy mode, fetched/retained v12 with `pending=false`, returned RadioShell to foreground and
  exposed the Ready automation marker without any credential copy or rotation. The retired Android
  token file was absent after migration.
- Rebuilt the current APK and updated the connected T56 in place with `adb install -r`. Its original
  install lineage and Device ID/config association remained valid; process-start refresh selected
  the production config version exposed by the Web endpoint, left `pending=false` and returned the
  device to RadioShell Ready without a reboot, data clear or PTT transmission.
- Added a protected receiver provisioning fallback for Android builds without `/system/bin/run-as`.
  It reports the non-secret Device ID and validates/installs a temporary ADB config through the
  `android.permission.DUMP`-protected receiver, preserving active/previous config semantics.
- Verified on the physical T99: private config provisioning, pinned TLS connection to the supplied
  self-signed server, token authentication, exact full-path room join, ready UI, service survival
  while the display was off, and a real reboot returning directly to the same ready room without a
  HOME chooser or operator input.
- Verified automatic self-signed trust separately on T99: removed the old app-private trust store,
  provisioned a config with no fingerprint, and observed Minimum recreate private trust, reconnect
  and return to the exact ready room without a certificate dialog.
- Verified all 93 FOSS debug JVM tests and the FOSS debug APK build after the T56 profile,
  provisioner and protected config-import additions.

## Known limitations / not falsely marked complete

- RYKS operator capture corrected the initial conservative front-key assumptions: the three-line
  key is F2/scan 60 and now toggles Device ID on a single press; green is DPAD_CENTER/scan 353; red
  is native POWER/scan 116 and deliberately retains Android's Power off / Reboot menu. The two keys
  below PTT are F8/scan 66 and F7/scan 65 for previous/next room selection. A physical hold from a
  verified display-OFF state now proves the OEM DOWN/UP edges and wake path. Android 8.1 logs showed
  that it suppressed the manifest receiver before application code ran, so the foreground service
  now registers the OEM PTT receiver at runtime and dispatches each edge without queuing. A physical
  display-off hold then transmitted successfully and returned to Ready after release without stuck
  TX. Live multi-room acceptance remains open.
  After installing the corrected APK, repeated physical F2 DOWN/UP events reached the identity-toggle
  path and RadioShell remained foreground instead of opening Settings.
  Android High Accuracy (`gps,network`) survives reboot. Two 120-second redacted probes produced no
  coordinates; the repeat saw GPS enabled, network provider unavailable and 16 almanac entries with
  zero ephemeris/SNR/used-in-fix. Keep APRS disabled, but do not claim permanent hardware inability
  until a controlled ten-minute open-sky GPS-only plus GPS/network test is also negative.

- Physical PTT while the T99 display is off has passed an operator test. A subsequent physical trace
  classified the labelled control as `KEYCODE_F1` 131 / scan 59 / deviceId 4 / source `0x101` /
  `gpio-keys`; repeat DOWN events occur while held and a normal UP releases TX. F2 is physically
  EXIT and is explicitly excluded from T99 PTT.
- T56 commissioning confirmed side keys as keyCode 260 (scan 138) and 266 (scan 59), physical
  screen-power OFF -> ON behavior, and screen-off PTT as keyCode 261/scan 216. T56's OEM keyguard
  consumed the first screen-off PTT before the Activity. The same firmware emits OEM PTT DOWN/UP
  broadcasts even with keyguard active, so the APK now forwards those T56-only broadcasts into the
  service-owned PTT path. A test that first verified `Display Power: state=OFF` then injected the
  captured raw event reached TX and was observed from T99; operator physical acceptance is still
  pending.
  The Android Power key mapping remains unassigned because Power policy may consume it before the
  app. A later direct operator hold established that T56's one-person key is scan 64/FN2 and Android
  keyCode 21 (`DPAD_LEFT`); the earlier scan 63/keyCode 264 attribution was incorrect.
- T56 keyguard can also consume the one-person key's raw event while the display is off. Firmware
  provides the matching `unipro.hotkey.p2.long` action, so the T56 hardware receiver now
  opens/foregrounds RadioShell and toggles the full-screen Device ID. A deduplication window prevents
  the simultaneous Activity raw-key path and OEM broadcast from toggling twice while awake. Raw
  corrected scan-64 show/hide and dedup tests pass while awake, and the operator physically
  confirmed Device ID display from the one-person key after installation. An injected scan-64 hold
  did not wake the explicitly asleep/OFF device, so screen-off identity behavior remains pending.
- Android cannot route an arbitrary F1 key to an ordinary app while another application owns the
  foreground. Instant T99 PTT recovery is therefore implemented for RadioShell and MinimumHome; a
  truly global path would require a separately tested OEM broadcast, privileged integration or
  provisioning-time keylayout remap. Media/headset keys already have the public MediaSession path.
- The first physical F1-from-dashboard acceptance exposed a cross-window event race: RadioShell
  opened and the original held press began TX. The installed hotfix now arms service and Activity
  release lockouts before launch and can force-stop any accidental TX; physical regression retest
  remains required before this path is marked PASS.
- The physical red-button path has passed acceptance. An isolated capture proved kernel scan 2 /
  `KEY_BACK` is vendor-remapped to Android `KEYCODE_DPAD_RIGHT`; the installed protected-exit path
  displayed the hold prompt and opened MinimumHome after a greater-than-five-second hold.
- Multiple configured room presets and one-second hold switching are implemented but have only
  been exercised with one live room. Permission-denied fallback and room changes during real
  traffic still need a multi-room acceptance test.
- Schema-3 cross-server/password/token switching is installed privately on T56 with separate
  connection IDs and the requested usernames; selected-channel restoration still needs the operator
  to switch between both live rooms.
- The checked-in backend intentionally has `autoConnect: false`, a placeholder host and no access
  token. The successful endpoint configuration remains local/device-private.
- T56 boot/dashboard and legacy shortcut behavior passed on the connected target. Each new firmware
  must still pass the no-ResolverActivity provisioning check.
- Pending-config promotion and failure rollback pass JVM tests. Physical T99 inspection after the
  schema-2 identity migration showed active v1003 with Config Profile `GYZ3DE` and Mumble username
  `E25FGL-T99`; a fresh launch connected and joined the configured room as Ready. A physical
  failed-candidate rollback injection remains open.
- The workstation reboot cleared the Windows USB/ADB wedge. The latest APK was installed and the
  guarded 30-second Wi-Fi/LTE outage restored both original settings and returned to Ready without
  operator intervention. A same-UID SIGKILL initially exposed T99's roughly 16-minute OEM service
  restart backoff; after adding the renewable watchdog lease, a second physical run produced a new
  PID in 23.9 seconds and restored RadioShell/Ready in 30.9 seconds without `am start` or PTT.
- Wake-screen, reconnect visual state, half-duplex and PTT-failure behavior still require the
  remaining supervised matrix in `RECONNECT_TEST_PLAN.md`.
- The RX state ordering fix has JVM coverage and is installed on T99. The operator subsequently
  confirmed a natural live `Ready -> speaker -> Ready` cycle: the initial Ready flash and stale
  speaker name are both gone.
- Provisioning now enables high-accuracy GPS/network on T99 and device-only GPS on T56; T56's
  network provider requires an interactive Google consent flow. The earlier headless run launched
  Minimum over Google's `ConfirmAlertActivity`, so it was not evidence of a firmware block. A fresh two-minute
  temporary probe accepted T56 standalone GPS at roughly 5 m accuracy (27 visible, 13 used, max SNR
  about 32.8), but T99 again produced almanac-only data with zero SNR/ephemeris/used satellites and
  no fix. Future tracking is hardware-gated to T56 and denied on T99/generic devices. Both expose
  Qualcomm A-GPS capability, yet XTRA is disabled, T56's SUPL host is malformed, and assisted
  operation remains unaccepted.
- T56's interactive location-consent flow is now physically verified. Provisioning uses Google
  Services Framework's own activity to reset `use_location_for_services` to `0`, opens the consent
  dialog, waits for the operator's positive action to persist `1`, and only then enables
  `mode=3`/`gps,network`. A following redacted 30-second probe returned a GPS fix at about 13 m and
  a network fix at about 29.21 m, so T56 network location is accepted. A-GPS remains a separate
  open acceptance item because SUPL/XTRA contribution was not demonstrated.
- T56-only APRS tracking is implemented behind the immutable profile gate. SmartBeacon, PTT and
  retry events share semantic duplicate suppression; stationary GPS uses a bounded 90-second
  acquisition window and a 30-minute poll; HTTPS send-only requires a documented 204 receipt before
  persisting the last successful position. The legacy T56 TLS path pins the APRS endpoint's ISRG
  Root X1 CA, and position reports are published with an optional validated Object label or the
  wildcard-friendly `VR-` plus six-character Device ID fallback. Object symbols now distinguish
  stationary, walking and vehicle states. The same beacon carries redacted GPS accuracy,
  battery/charging/temperature, Wi-Fi,
  mobile-network and free-storage Health fields without a separate packet.
  An open-sky T56 run received a GPS fix and a live APRS-IS receipt. T99 has no tracking manager or
  location request.
- APRS tracking's canonical packet, identity, Health comment, receipt, privacy and migration rules
  are documented in [APRS_TRACKING.md](APRS_TRACKING.md). Preserve the Object form and use `VR-`
  fallback unless an explicit valid Object name is configured; old callsign-owned packets already
  accepted by APRS-IS cannot be deleted from APRS.fi history.
- Config schema 3 now accepts optional `tracking.aprs.objectName` (1-9 safe ASCII characters).
  Android normalizes it to APRS' nine-byte field; omission preserves `VR-<DeviceID>`. Changing the
  name resets semantic duplicate state so the next accepted fix publishes the new Object identity.
- The reconnect harness now validates that both original network settings are exactly `0` or `1`,
  verifies their restored values, and detects Ready through the stable accessibility marker
  `minimum-state-ready` rather than localized UI text.
- Thai TTS depends on the Android TTS engine and Thai voice data installed on each device. Missing
  Thai data does not block radio operation.
- Boot activity launch can be blocked by newer Android/OEM policy. T99 is API 22 and passed an
  actual reboot-to-ready-room test; a newer-device foreground-service/notification fallback remains
  future work.

## Immediate next work

1. With the operator present, verify one physical F1 press from MinimumHome opens/reconnects without
   TX, then press again only after Ready; decide whether T99 provisioning needs a tested global
   keylayout/OEM path for unrelated foreground apps.
2. Execute the supervised server-restart, long-outage, reconnect visual, wake-screen, half-duplex
   and PTT-failure portions of `RECONNECT_TEST_PLAN.md`.
3. Complete RYKS real display-off PTT acceptance, then verify its F8/F7 room actions against at
   least two live configured rooms and reconfirm the corrected Device ID/Power behavior.
4. Complete T56 app-private side/power trace, room and supervised PTT tests without copying T99's
   F1 mapping.
5. Exercise two or more room presets, denied-room fallback and safe room switching during traffic.
6. Run physical failed-candidate rollback acceptance, then add config signatures.
7. Extend the new private key diagnostics with config/audio health, then decide the dedicated radio
   flavor/application ID.
8. Use the maintained GitHub Issue forms and triage labels for field-test reports. Before merging
   or publishing an APK, follow the separate gates in
   [GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md); a passing debug build alone is not a
   signed release.

APRS-specific remaining risks are tracked in [APRS_TRACKING.md](APRS_TRACKING.md): A-GPS attribution,
certificate-rotation integration coverage and confirmation that the server-advertised HTTPS send-only
contract remains available.

The detailed Technical Brief comparison and implementation order are maintained in
`docs/TECHNICAL_BRIEF_GAP_ANALYSIS.md`. The bounded Sol/Luna delegation contract is in
`docs/CODEX_WORKFLOW.md`.

## Important safety rules

- Never commit the Mumble access token, GitHub credentials, private keys or device-specific secrets.
- Do not attempt to rewrite the T99 USB/ADB serial from an unprivileged script; use ADB
  `transport_id` to disambiguate identical units and use Minimum Device ID for app identity.
- Keep T99 F2 permanently reserved for physical EXIT. Keep T56 F1 permanently reserved from PTT;
  T56's captured primary PTT is vendor keyCode 261.
- Keep RYKS scans 216 and 249 as PTT while the OEM broadcast has no scan-code extra; assigning scan
  249 to another action could cause an unintended transmission.
- Keep the normal Mumla build working while the radio interface is developed.
- Merge changes only after their required automated gates pass and any production dependencies are
  provisioned; hardware-only acceptance may remain explicitly deferred when no device is attached.
- Do not publish an APK as a GitHub Release until the signing identity, CI artifact provenance,
  checksums, release notes and explicitly accepted hardware limitations satisfy the release gate.
