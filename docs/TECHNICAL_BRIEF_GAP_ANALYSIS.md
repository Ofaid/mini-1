# Technical Brief gap analysis

Last reviewed: 2026-08-12

This document maps the supplied Public PoC Radio Client Technical Brief to the code that actually
exists. `PROJECT_STATUS.md` remains the short hand-off source of truth; this file is the detailed
implementation checklist. A checked item means the behavior exists and has proportionate evidence,
not merely that a class or placeholder exists.

## Executive result

Minimum now has a working end-to-end radio-client slice on T99. Build/branding, persistent public
device identity, Last Known Good config storage/validation, automatic certificates, boot and
provisioning, service-owned PTT safety, token authentication, auto-connect and exact room join are
implemented. This critical path has passed a real server and reboot test:

```text
active radio config
  -> create/select the Mumble server
  -> resolve public access tokens
  -> connect automatically
  -> resolve and join a room by full path
  -> expose RX/TX/connection state in the radio UI
  -> change room from physical preset keys
```

The remaining MVP risk is hardware and lifecycle acceptance rather than basic connection wiring:
T56 and RYKS supervised hardware/PTT evidence, multi-room traffic tests, extended lifecycle/audio
evidence and release hardening.

The post-brief T56 tracking extension is operational: immutable T56-only capability gating,
bounded GPS acquisition, configurable APRS Object identity with `VR-<DeviceID>` fallback, state
symbols, compact Health comment
and acknowledged HTTPS send-only transport have passed a live open-sky/APRS.fi check. Its canonical
contract is [APRS_TRACKING.md](APRS_TRACKING.md); T99 tracking remains deliberately unsupported.

T99 physical screen-off PTT has passed an operator test. Physical capture now identifies the
labelled PTT as raw gpio-keys `KEYCODE_F1` 131 / scan 59 / source 0x101 and identifies F2 as EXIT;
T99 rejects F2 as PTT. MediaSession remains an alternate headset/media path.

## Phase-by-phase status

| Brief phase | Status | Evidence already present | Remaining work |
| --- | --- | --- | --- |
| 0 — Baseline | Partial | Upstream/Humla retained; FOSS debug tests and APK build; Minimum name/icon | Record a clean upstream voice/PTT baseline; choose a distinct application ID; decide radio/diagnostic flavor structure |
| 1 — Radio Shell | Mostly complete | Dark `RadioShellActivity` loads LKG config, restores the selected full-path channel, auto-connects/reconnects, keeps the Android status bar visible, shows connection/RX/TX/access state and supports verified hardware/MediaSession PTT paths; real T99 boot-to-ready passed | Exercise multiple channel presets and decide dedicated radio application ID/flavor |
| 2 — Device Identity | Mostly complete | Six-character `SecureRandom` identity, persistence, validation, tests and ADB/system-shell-protected Config Profile assignment | Add protected on-device admin UI/gesture for regeneration and acceptance tests across update/reboot/clear-data |
| 3 — Remote Configuration | Mostly complete | Schema 3 keyed connections and per-channel auth, selected-channel persistence, embedded fallback, Device-ID-addressed Vercel device fetch without manual token enrollment, portal model/device normalization, limits, validation, active/previous/pending cache, downgrade rejection, startup/six-hour/network-return refresh, service-owned RX/TX idle candidate trial, commit after channel join, explicit rollback and JVM/web tests | Config signature verification and physical success/failure candidate acceptance |
| 4 — Rooms and Tokens | Mostly complete | Selected-channel public tokens feed Humla authentication without DB/preference/log persistence; channels can cross server/password/token boundaries and reconnect as needed; typed presets, exact full-path lookup, restored selection and one-second Up/Down hold; one live nested-channel T99 test passed | Multi-server live test, permission-denied/default-channel fallback evidence |
| 5 — Hardware PTT | T99 and RYKS core pass / T56 physical acceptance partial | T99 ten-button map, T56 eleven-control input capture and RYKS ELINK/ym_258 profile; RYKS front/side controls captured as F2 Device ID, F8/F7 rooms, DPAD_CENTER green and native POWER red; guarded provisioning; profile-specific PTT rules; physical RYKS display-off TX/release pass through a foreground-service runtime receiver; private bounded diagnostics; dashboard recovery; throttled reconnect; Activity/MediaSession paths; local warning; screen wake; TX timer; release paths and 120 s watchdog | T56 app-level side/power trace and physical foreground/screen-off PTT matrix; T99 dashboard-F1 acceptance; RYKS live multi-room test; OEM/vendor/global path only where required |
| 6 — Hardening | Mostly implemented, acceptance incomplete | Automatic certificate/boot, transport-only 15/30/60-second reconnect with persisted 15-second attempt guard and rejection hold, guarded T99 network-loss PASS, process watchdog SIGKILL PASS, managed chat heads-up suppression, automatic preprocessor/half-duplex/TTS, teardown unmute, config rollback/downgrade and managed self-signed trust with optional pin | Long-outage/server/audio/wake evidence, battery optimization, bundled voice prompts, protected-token store, config signatures and sanitized diagnostics/security review |
| 7 — Release | Integrated CI passed / release not accepted | Public GitHub repo, draft PR, GPLv3 source base, architecture/runbook/hardware docs, structured Issue/PR templates, Android/Web CI run `31306714812` passing on commit `6ee5c5e6`, and manual signed APK workflow with checksum/signature verification | Configure/protect release signing; run a tagged release candidate; approve release notes/known limitations; add instrumentation/device video and remaining LTE/Wi-Fi/screen-off/reconnect evidence |

The post-brief T56 Location/APRS extension is a live pass: quality/stale-fix filtering, a bounded
stationary GPS window, adaptive movement states, `VR-` Object identity, state icons, Health comment,
HTTPS receipt and APRS.fi indexing are verified. A-GPS attribution, endpoint certificate-rotation
integration coverage and continued public-location privacy review remain open.

## Acceptance-test coverage

- Device identity: validation/unit coverage exists; lifecycle acceptance matrix remains manual.
- Config: parsing, downgrade, pending promotion, explicit rollback, refresh gating and RX/TX idle
  policy tests exist. Signature verification and physical candidate success/failure evidence remain.
- Rooms/tokens: integrated end to end and passed with one nested live room; multiple presets and
  denied-room behavior remain unproven.
- PTT: T99 physical screen-off PTT passed; the labelled button is captured as F1/scan 59/gpio-keys,
  F2 is EXIT and private key diagnostics are installed. Short accidental exits, five-second F2 hold
  and one-second Up hold passed without TX. RYKS physical display-off capture confirms vendor
  keyCode 285 emits both Zello-style DOWN/UP edges and wakes RadioShell. Android 8.1 suppressed the
  static receiver, so runtime delivery owned by the foreground service is installed without
  queuing. A subsequent physical display-off hold transmitted and released successfully, returning
  RadioShell to Ready without stuck TX.
  Physical F1-from-dashboard and T56 device acceptance also
  remain open; T56's primary PTT is vendor keyCode 261 and F1 is explicitly reserved for Menu.
- Audio: no maintained LTE/Wi-Fi/Bluetooth/manual test report yet.
- Lifecycle: actual T99 reboot-to-ready-room, short display-off persistence, 30-second network loss
  and process-death watchdog recovery are verified; long outage and newer Android boot restrictions
  remain open.
- Location/APRS: T56 live Object/Health/receipt passed and T99 isolation passed. Historical
  callsign-owned test packets cannot be deleted from APRS.fi; only the `VR-` Object format is current.

## Implementation order from here

1. Continue `RECONNECT_TEST_PLAN.md` with server restart, long outage, reconnect visual, wake,
   half-duplex and supervised PTT-failure evidence; network and process-death cases already pass.
2. Complete T56 app-private side/power capture and supervised foreground/display-off PTT tests,
   plus the RYKS live-room matrix; do not copy T99 F1/F2 assumptions. Guarded
   provisioning exists for both models.
3. Exercise multiple room presets, denied-room fallback and watchdog release with an operator present.
4. Complete pending-config physical acceptance, then add signed config and hidden diagnostics.
5. Complete the hardware/lifecycle/security acceptance matrix, then introduce the dedicated application
   ID/flavor and release pipeline in an isolated commit. The application ID must be chosen before
   production provisioning because changing it later creates a separate Android app/data identity.

## Explicit non-goals retained from the brief

No GitHub login/token in the APK, chat, direct message, public server browser, channel tree in radio
UI, recording, firmware flashing/root, Mumble/Opus rewrite, bridge, or OTA APK update is part of the
MVP. The Vercel/Cloudflare portal is now an implemented configuration control plane, but it remains
separate from Android binary release and does not store secrets in the public Pages backend.
