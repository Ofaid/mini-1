# Minimum acceptance matrix

| Area | Current result | Evidence / next action |
|---|---|---|
| FOSS debug unit tests | PASS | 121 app tests and 11 Humla tests; zero failures/errors/skips. |
| FOSS debug APK build | PASS | `:app:assembleFossDebug` |
| FOSS release APK assembly | PASS LOCALLY / UNSIGNED | `:app:assembleFossRelease`; signing and tagged GitHub provenance remain open. |
| Android full Lint | TARGETED CORRECTNESS PASS / PRE-EXISTING BASELINE REMAINS | Fresh `lintFossDebug` reports 21 errors/330 warnings. The requested permission, lifecycle/wakelock, receiver-flag, locale-quantity, MissingSuperCall and dedicated-device export findings are resolved or narrowly documented/suppressed; remaining errors are legacy GestureBackNavigation (1), database Range (6) and UseAppTint (14). No Lint baseline is committed and Lint was intentionally not driven to zero. |
| Existing-device updater integration | PASS IN STATIC/AUTOMATED TESTS / PHYSICAL OPEN | PowerShell AST and 33 policy/fixture/state-machine tests (including the real built debug APK) cover non-creating legacy run-as and focused Ready-UI proof, wrong-package/not-Ready/unfocused refusal before receivers, signer/no-mutation, Linux/Windows `apksigner` discovery, recovery, model routing, reboot correlation and partial sessions; cellular verifier, exact workflow allowlists and full `apksigner` contract also pass. E7ROW7 same-debug-signer update and T99/RYKS physical acceptance remain open. |
| Fresh provisioner Release preflight | PASS IN 16 AUTOMATED TESTS / PHYSICAL OPEN | Extracted Release mode verifies the exact allowlist/hashes, APK checksum/package/version and exactly one manifest-bound signer before ADB resolution, then repeats the complete binding immediately before native `adb install`; local build, out-of-bundle paths and post-preflight replacement fail closed. Physical install remains deferred until hardware is connected. |
| GitHub Actions integrated CI | PASS | Run `31306714812` on commit `6ee5c5e6`: Android unit tests/debug APK/unsigned release assembly and Portal tests/type-check/production build all passed. |
| T99 ADB install | PASS | T99 serial `12344321` |
| T99 Device ID format/persistence | PASS | `DeviceIdentityManagerTest`; startup integration added |
| Automatic certificate generation | IMPLEMENTED | Must rerun only on a disposable fresh app-data test |
| Media-style PTT foreground | IMPLEMENTED | Service MediaSession path |
| T99 physical PTT identity | PASS | Physical button is gpio-keys `KEYCODE_F1` 131, scan 59, deviceId 4, source 0x101; F2 is EXIT |
| T99 physical screen-off PTT | PASS (operator observed) | F1 path works with screen off; exact foreground Android metadata and release captured subsequently |
| T99 F2 EXIT isolation | PASS IN CODE/BEHAVIOR | T99 forcibly defaults to F1, rejects F2 as PTT and routes F2 to recovery dashboard |
| Media/headset screen-off PTT | IMPLEMENTED ALTERNATE | MediaSession remains active for headset/media PTT alternatives; not the labelled T99 PTT button |
| Configured PTT watchdog | PASS IN JVM / PHYSICAL TIMING OPEN | Validated `maximumTxSeconds` 1..120 reaches the service, arms on hold/toggle and exported legacy TALK transitions, captures each transmission limit and releases safely on policy change. Repeated TALK-on cannot extend the deadline, and timeout lockout requires a release edge; add a short-limit and default-120 physical timing run. |
| Disconnect releases TX | IMPLEMENTED | Service lifecycle path; add manual screen-off test |
| Service/audio/wakelock teardown | PASS IN JVM/STATIC / PHYSICAL OPEN | Best-effort cleanup runner preserves later actions after failures; Humla destroy blocks reconnect, disconnects handlers/audio/SCO and releases partial/proximity/screen wake locks through idempotent paths. Validate dumpsys/logcat after a physical destroy/reconnect cycle. |
| Boot receiver registration | PASS | Manifest and receiver present |
| T99 simulated boot launch | PASS | Activity appeared after valid simulated broadcast |
| T99 radio dashboard pages | PASS | Installed APK: Minimum -> Settings swipe path |
| T99 ten-button map | PASS | Kernel capture plus Android metadata/keylayout: Power, F1 PTT, volumes, MENU/EXIT, up/down, green/red documented |
| T99 accidental-exit guard | PASS | Red is app-level `KEYCODE_BACK`/scan 60/gpio-keys; release-timestamp fallback makes a 5.06 s hold open dashboard even if UI callback is delayed; green reopens RadioShell |
| T99 room-key hold | PASS WITH ONE ROOM | Raw Up hold >1 s executed once and returned Ready; multi-room direction still needs live evidence |
| PTT dashboard recovery | HOTFIX INSTALLED / PHYSICAL RETEST REQUIRED | First physical run opened RadioShell but cross-window F1 started TX; release-required lockout now arms in service and Activity before launch, build passes and installed app is Ready |
| Managed-radio configured-room TX gate | PASS IN JVM / LIVE TRANSITION OPEN | Pure policy requires synchronized session, PTT mode and verified room readiness for every service input path |
| Arbitrary-app global F1 capture | UNSUPPORTED BY STANDARD API | Requires OEM/privileged input or a tested keylayout remap; MediaSession covers only media-style keys |
| T99 system HOME chooser | PASS | Launcher3 opens directly; ResolverActivity absent |
| T99 Launcher3 recovery shortcut | PASS | Minimum and Settings icons visible after provisioning |
| T99 reboot radio client | PASS | Real reboot resumes `RadioShellActivity`, auto-connects and rejoins the exact configured room; no chooser |
| Newer OEM boot policy | OPEN | Add notification/foreground-service fallback |
| Zello user-0 removal | PASS | `pm uninstall --user 0 com.loudtalks` verified |
| Zello repeat script dry run | PASS | `remove-zello-t99.ps1 -WhatIf` |
| Static backend JSON | PASS | Parsed with PowerShell `ConvertFrom-Json` |
| GitHub Pages workflow | CONFIGURED / RECOVERY ONLY | Deploy occurs after workflow reaches `main`; managed devices use the Vercel device endpoint |
| Vercel/Cloudflare admin portal | PASS IN WEB / EXISTING PRODUCTION SMOKE / D1 ACTIVATION OPEN | First-run admin, pending-device queue, device CRUD, Schema-3 structured editor, Device-ID lookup endpoint and KV persistence; distributed login admission uses atomic D1 fixed-window buckets and fails closed, with Vercel secret provisioning/deployment smoke still pending; admin/Cloudflare secrets stay server-side |
| Web `radio.defaultChannel` editor | PASS IN WEB | **Channels & default** selector and per-channel **Set default** persist `radio.defaultChannel` and advance `configVersion`; handset Last Selected Channel still wins when valid |
| Android config embedded fallback | PASS | Asset + validation in repository |
| Android remote config fetch/cache | PASS IN JVM / RYKS PHYSICAL | Startup/six-hour/network-return refresh, in-flight guard, pending staging and LKG fallback; RYKS tokenless OTA activated portal v12 and a real reboot returned Ready with v12 active and `pending=false` |
| T56 APK update + remote config | PASS PHYSICAL | Current APK installed with `adb install -r`; install lineage persisted, process-start refresh matched the production endpoint, active config had no pending candidate and RadioShell returned Ready without reboot/data clear/PTT |
| Public access-token resolver | PASS | JVM tests cover trimming, case, ordering, duplicates and malformed/protected entries |
| Remote room path selection | PASS | Exact full-path resolver JVM tests plus live T99 join to the supplied nested room |
| Connection-time token integration | PASS | Live T99 authentication through existing Humla extras; token remained local and was not logged |
| Config-driven auto-connect/reconnect | PASS | Live T99 ready state and automatic return after a real reboot |
| Indefinite reconnect policy | PASS NETWORK/PROCESS / EXTENDED MATRIX OPEN | T99 30-second Wi-Fi/LTE outage restored settings and Ready; process watchdog restored PID in 23.9 s and Ready in 30.9 s; server restart/long outage remain |
| T99 process-death watchdog | PASS | Same-UID SIGKILL, no force-stop/manual relaunch/PTT; renewable 30-second lease bypassed OEM roughly 16-minute service restart backoff |
| Reconnect full-screen UI | IMPLEMENTED / T99 VISUAL OPEN | Whole-screen connecting/reconnecting state, attempt count and no false Ready before room join |
| PTT local-delivery warning | IMPLEMENTED / SUPERVISED TEST OPEN | Offline/no-encoded-packet tone and full-screen failure; server receipt still requires a second listener |
| Radio audio defaults | PASS IN CODE / DEVICE AUDIO OPEN | Preprocessor, half-duplex and TTS forced for T99/T56; normal PTT confirmation click forced off while failure alert remains; VAD setter and teardown unmute corrected |
| Thai TTS selection | IMPLEMENTED / DEVICE VOICE OPEN | Requests `th-TH` only when installed engine reports support; radio continues if voice data is missing |
| RX/TX display wake | IMPLEMENTED / T99 OPEN | Five-second wake on RX/TX/disconnect edge; OEM/API-22 behavior requires physical verification |
| Hardware-first RadioShell UI | PASS IN BUILD / T99 VISUAL OPEN | Touch PTT removed, compact 132dp layout, service-owned speaker list and TX elapsed timer |
| Simultaneous talker display | PASS IN JVM / T56 VISUAL OPEN | Complete ordered snapshot; T99 shows 2 lines, larger displays 4; overflow uses the final line as `+N` and preserves the full list for accessibility |
| Managed self-signed TLS auto-trust | PASS | Cleared old app trust, provisioned no-pin config, app recreated private trust and returned to the exact ready room without a dialog |
| Optional self-signed TLS pin | PASS | Exact SHA-256 pin created app-private trust and connected; mismatch remains fail-closed |
| Active config downgrade rejection | PASS | Repository JVM tests reject a lower version and allow same/newer versions |
| Pending config idle gate | PASS IN JVM | Candidate blocked during RX, TX and connection transitions; service tracker covers talk/shout/whisper and disconnect clear |
| Pending config promotion/rollback | PROMOTION PASS T99 / FAILURE INJECTION OPEN | Active v1002, no pending and previous v1001 physically verified; failed-candidate rollback still needs live injection |
| Screen-off connection persistence | PASS | T99 display OFF/dozing for 10 seconds with `MumlaService` still started and no disconnect |
| Multi-room preset switching | IMPLEMENTED IN CODE | One-second Up/Down hold joins directly; requires a live config with at least two rooms |
| Dark mode default | PASS | Fresh/unset preference resolves to `forceDark`; update installed and visually checked on T99 |
| Radio shell | PASS ON T99 / RYKS BADGE VISUAL PASS | Dark ready/RX/TX/status UI, configured Channel Alias badge without a redundant `CHANNEL` prefix, Device ID overlay and hardware-only PTT |
| T56 profile detection | PASS | `UNIPRO/ZX` selects T56 on the connected device; PTT is keyCode 261 and F1 is explicitly rejected |
| T56 physical input inventory | PARTIAL PASS | Side keys are Android `260`/`266` and PTT is `261`/scan `216`; physical screen-power OFF -> ON behavior passed, but its Android key mapping remains unassigned pending isolated raw capture |
| T56 provisioning | PASS / PRIVATE CONFIG INSTALLED | Report/WhatIf/Force passed on the connected `UNIPRO/ZX`; T56 Device ID `P1L4A0` now has the two private schema-3 server/channel entries with usernames `E25FGL-T56` and `E25FGL-56`; credentials were copied only into app-private storage |
| Small-radio Device ID view | PASS AWAKE / SCREEN-OFF OPEN | Service name and inline Device ID are invisible but retain their layout rows. T99 scan 139 toggles the full-screen overlay. A direct T56 operator hold disproved the earlier scan-63 mapping and established the one-person key as scan 64/FN2/Android `DPAD_LEFT`; awake show/hide and dedup pass, and the operator physically confirmed Device ID display after installation. Firmware exposes `unipro.hotkey.p2.long`, but an injected scan-64 hold did not wake an explicitly asleep/OFF device, so screen-off identity remains open |
| T56 screen-off PTT | END-TO-END RAW EVENT PASS / OPERATOR RETEST | After `mWakefulness=Asleep` and `Display Power: state=OFF` were explicitly verified, scan 216/keyCode 261 woke T56, the OEM DOWN/UP receiver drove PTT and T99 observed the T56 talk state; operator physical hold remains the final acceptance check |
| RYKS screen-off PTT | PASS | From verified `Asleep` / display OFF, physical holds emitted `com.zello.ptt.down/up` and woke RadioShell. Android 8.1 suppressed the manifest receiver, so the foreground service now registers it at runtime without queuing; the retest transmitted successfully and returned to Ready after release without stuck TX |
| Location provisioning | PASS / T56 CONSENT PHYSICALLY VERIFIED | T99 provisions high-accuracy GPS/network; T56 defaults to GPS-only and its explicit operator flow verified Google consent before ending at mode 3 with GPS/network |
| T56 GPS fix | PASS | Two-minute temporary probe: about 5 m accuracy, 27 satellites visible, 13 used, max SNR about 32.8 |
| T56 network-location fix | PASS AFTER CONSENT | Redacted 30-second probe returned a network fix at about 29.21 m accuracy while GPS also fixed at about 13 m |
| T99 GPS fix / tracking capability | UNSUPPORTED | Repeated two-minute-plus probes see almanac entries but zero SNR/ephemeris/used satellites and no fix; the app hardware capability gate denies T99 tracking |
| RYKS GPS/network fix | NOT ACCEPTED / OUTDOOR TEST OPEN | Repeat 120-second redacted probe: GPS enabled, network provider unavailable, 16 almanac entries, zero ephemeris/SNR/used-in-fix and no coordinates. APRS remains disabled; this is not proof of permanent hardware incapability without a controlled open-sky run |
| T56 tracking unit policy | PASS IN JVM / DEVICE BEACON PASS | Coordinator covers jitter, movement, turns, transitions, stale fixes, PTT, concurrency, restart restore and newest-pending replacement; the T56 regular GPS request is removed after the 90-second acquisition window |
| T99 tracking isolation | PASS IN CODE/ADB | T99 config gate returns disabled, no tracking manager is constructed, and post-install `dumpsys location` had no `se.lublin.mumla` request |
| APRS HTTPS send-only response policy | PASS IN JVM | HTTP 204 with `X-Packetsrcvd > 0` is success; missing receipt is uncertain; auth rejection is permanent; 5xx/409 are retryable |
| T56 live APRS Object report | PASS ON DEVICE | Open-sky T56 fix produced a `VR-` Device ID Object report and the APRS-IS endpoint returned a positive packet receipt; APRS.fi indexed the Object position |
| T56 APRS health comment | PASS ON DEVICE / JVM | Position comment carries battery, charging, battery temperature, Wi-Fi RSSI and storage; mobile type/RSSI is included when exposed and otherwise marked `NA` |
| Configurable APRS Object name | PASS IN JVM | Optional `tracking.aprs.objectName` is validated, uppercased and padded to nine bytes; omission retains `VR-<DeviceID>` and identity changes reset duplicate state |
| APRS stale callback invalidation | PASS IN JVM / DEVICE REGRESSION OPEN | Generation tickets invalidate in-flight results synchronously on stop/reconfiguration; stale receipt/failure callbacks cannot persist state or retry, and throwing/rejected transport paths are released. Physical stop/reconfigure regression awaits T56 attachment. |
| A-GPS assistance | OPEN | Both advertise Qualcomm A-GPS capability; XTRA is disabled and T56's SUPL host is malformed, so the successful T56 GPS/network fixes do not prove assisted-GPS operation |

## Release gate

Do not call the Minimum radio PoC radio-ready until screen-off PTT, network-loss TX release,
certificate first-run, boot behavior and T56/T99 profile selection have each passed on the target
hardware or been explicitly marked as unsupported. Do not publish a GitHub APK Release until the
signing, reproducibility, checksum, release-notes and known-limitations checks in
[GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md) are complete.
