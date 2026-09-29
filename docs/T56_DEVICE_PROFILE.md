# T56 device profile

Managed cellular provisioning and its roaming-cost opt-out are documented in
[CELLULAR_PROVISIONING.md](CELLULAR_PROVISIONING.md). T56 uses an automatic LTE-capable mode with
legacy fallback; LTE-only is never provisioned.

Last captured: 2026-08-07

T56 is the project name for the UNIPRO/ZX Android PTT radio identified below. It is the maintained
generic/model name for this profile. Do not apply T99 key assumptions to this device: its labelled
Menu key is F1, while its labelled PTT key uses a vendor DTT key path.

## Verified device facts

| Item | Value |
| --- | --- |
| Manufacturer / brand | `UNIPRO` / `ZX` |
| Model / device | `ZX` / `L809` |
| Android | 5.1.1 / API 22 |
| Build fingerprint | `qcom/unipro/unipro:5.1.1/LMY47V/swscm10081739:user/test-keys` |
| SoC / hardware | Qualcomm MSM8909 / `qcom` |
| ABI | `armeabi-v7a`, `armeabi` |
| Reported RAM | approximately 403 MiB |
| Display | 160 x 128, density 98 |
| USB functions | `diag,serial_smd,rmnet_qti_bam,adb` |
| OEM HOME | `com.android.launcher3/.Launcher` |
| Lab Wi-Fi | connected to the configured lab SSID during capture |

The public profile does not record the handset serial, network address or other unique device
identifiers. `scripts/prepare-t56.ps1` auto-detects exactly one authorized `UNIPRO/ZX` device or
requires an explicit `-Serial`/`-TransportId` when selection is ambiguous.

## Input devices

- `gpio-keys`: F1-F7, HOME, HELP, BACK, CHAT and vendor keylayout mappings
- `rotary_switch`: discrete volume-knob clicks
- `qpnp_pon`: power-related input
- `msm8909-skue-snd-card_Button_Jack`: headset/media controls
- `msm8909-skue-snd-card_Headset_Jack`: headset switch state

The active GPIO layout is `/oem/usr/keylayout/gpio-keys.kl`. The rotary layout is
`/system/usr/keylayout/rotary_switch.kl`.

## Physical-button capture

The operator pressed the labelled controls in a fixed sequence while Zello was force-stopped.

| Physical control | Linux event | Scan code | Android mapping/evidence | Minimum behavior |
| --- | --- | --- | --- | --- |
| Up | `KEY_F3` | 61 | `DPAD_UP` / keyCode 19 | Hold 1 s: previous channel |
| Down | `KEY_F4` | 62 | `DPAD_DOWN` / keyCode 20 | Hold 1 s: next channel |
| OK | `KEY_F2` | 60 | `DPAD_CENTER` / keyCode 23 | Confirm/rejoin selected channel |
| Back | `KEY_BACK` | 158 | `BACK` / keyCode 4 | Hold 5 s: recovery dashboard |
| Menu | `KEY_HOME` | 102 | OEM layout maps to F1 / keyCode 131 | Reserved; never T56 PTT |
| One-person key | `KEY_F6` | 64 | OEM `FN2`; observed Android keyCode 21 (`DPAD_LEFT`); firmware long-hold broadcast `unipro.hotkey.p2.long` | Hold 1 s toggles full-screen Device ID |
| Three-person key | `KEY_F7` | 65 | `DPAD_RIGHT` / keyCode 22 | Diagnostic/reserved |
| Side key below PTT (upper) | `KEY_HELP` | 138 | OEM `DTT_SOS`; observed Android keyCode 260 (`NAVIGATE_PREVIOUS`) | Diagnostic/reserved |
| Side key below PTT (lower) | `KEY_F1` | 59 | OEM `FN3`; observed Android keyCode 266 (`STEM_2`) | Diagnostic/reserved |
| PTT | `KEY_CHAT` | 216 | OEM `DTT_PTT`; observed Android keyCode 261 | Primary PTT |
| Volume knob clockwise | `KEY_VOLUMEUP` | 115 | OEM rotary maps to F11 / keyCode 141 | Android/OEM volume path |
| Volume knob counter-clockwise | `KEY_VOLUMEDOWN` | 114 | OEM rotary maps to F12 / keyCode 142 | Android/OEM volume path |
| Screen power | physical press toggles display | Android mapping not isolated; Power policy may consume it before the app | Controlled OFF -> ON behavior passed; Android keyCode remains unassigned until an isolated raw capture |

The two side-key Android codes were confirmed in the foreground app trace. Screen power was
confirmed with controlled OFF -> ON presses; Minimum intentionally wakes its status surface when
PTT is pressed while the display is off. The screen-power key is not assigned to PTT or channel
selection, and the repeated `scanCode 63 / keyCode 264` event is not attributed to Power without
an isolated Linux capture.

## PTT policy

T56 must never inherit T99's F1 PTT rule because T56 Menu is delivered as F1. Minimum recognizes
only the captured vendor PTT keyCode `261` plus standard media/headset alternates. Managed defaults
persist 261 as the push key and suppress the normal Mumla PTT confirmation click while retaining
the failure tone and service-owned 120-second watchdog.

The OEM WindowManager trace explains the original two-press screen-off behavior: the first
keyCode 261 press is processed with `keyguardOn=true`, wakes the display and is not delivered to
`RadioShellActivity`. The firmware nevertheless sends `unipro.hotkey.ptt.down` and
`unipro.hotkey.ptt.up` for the same physical press. Minimum's T56-only manifest receiver forwards
those actions to the same service-owned readiness, watchdog and release path. After explicitly
verifying `mWakefulness=Asleep` and `Display Power: state=OFF`, a real-device raw scan-216 hold
produced TX that the connected T99 observed as the T56 Mumble user; a physical operator-button
retest remains required after the latest install.

The same keyguard limitation applies to the one-person key: its raw key event may not reach
`RadioShellActivity` while the display is off. T56 firmware emits `unipro.hotkey.p2.long` after a
roughly one-second hold, so the T56-only hardware receiver brings the shell forward and requests
the same full-screen Device ID toggle. A 750 ms Activity-side deduplication window prevents the raw
key handler and OEM broadcast from toggling the overlay twice while the display is already on.
The earlier scan-63/FN1 assignment was rejected after a direct operator hold produced the stable
scan-64/FN2, Android `DPAD_LEFT` path with more than 100 repeat events. Automated raw-event checks
now pass for show/hide and deduplication while the display is on, and the operator physically
confirmed that holding the one-person key displays the Device ID after the corrected APK install.
An injected scan-64 hold did not wake an explicitly asleep/OFF device, so screen-off identity
display remains unaccepted.

## GPS and location acceptance

T56 advertises GPS/network location hardware, loads Qualcomm `gps.default.so`, and exposes the
Qualcomm location stack. Its firmware declares an A-GPS provider, NTP, SUPL 1.0, LPP and a SUPL
host, but the host string is malformed and XTRA download URLs are disabled, so assisted operation
is not accepted merely from configuration flags.

After Location was enabled, a temporary two-minute probe produced a real GPS fix with
approximately 5 m reported accuracy: 27 satellites visible, 13 used, two with ephemeris and maximum
SNR about 32.8. A Google network-location `ConfirmAlertActivity` also appeared, proving the earlier
headless result was not evidence that firmware disabled the provider: Minimum was launched over the
dialog before an operator could answer it. Standalone GPS is accepted for future tracking
experiments; the consent workflow and network-derived positioning are accepted below, while A-GPS
assistance remains unaccepted.

On 2026-08-07 the operator consent path was completed on the connected T56. Firmware bytecode
confirmed that Google Services Framework's `SET_USE_LOCATION_FOR_SERVICES` activity accepts a
`disable` extra and that the consent dialog writes `use_location_for_services=1` only on its
positive button. Provisioning now resets the value to `0` through that system activity, waits for
the operator's positive action, then enables `mode=3` with `gps,network`. The physical run ended in
that state and reopened Minimum. This accepts the consent workflow and high-accuracy configuration;
it does not by itself prove a network-derived position or working A-GPS assistance.

A redacted 30-second probe immediately after consent then returned both providers: a GPS fix at
about 13 m accuracy and a network fix at about 29.21 m accuracy, with 29 satellites visible and
nine used in the GPS fix. Coordinates remained hidden and the temporary helper was removed.
Network location is therefore accepted on this T56. This still does not prove SUPL/XTRA-assisted
GPS because the probe cannot attribute the GPS time-to-fix to a particular assistance mechanism.

### Tracking runtime policy

The T56-only tracking manager uses a short-lived regular GPS request while stationary and removes
the request after a 90-second acquisition window. It schedules the next stationary check for 30
minutes, so GNSS is not left continuously active. Walking and vehicle states use adaptive sampling
for movement analysis; SmartBeacon transmission remains a separate decision and never beacons faster
than one minute. PTT evaluates only the most recent accepted fix and never waits for GPS before
voice transmission. An open-sky run received a quality-passing fix and a live APRS-IS receipt.
T99 is denied at the immutable hardware gate and has no tracking location request.

The APRS comment also reports a compact health snapshot: GPS accuracy, battery percentage/charging,
battery temperature, Wi-Fi RSSI, mobile type and RSSI when the firmware exposes them, and free
storage. Unavailable values are explicit `NA` markers and do not block a position beacon.
The position is an APRS Object using optional `tracking.aprs.objectName` or, when absent, `VR-` plus
the six-character Device ID; stationary, walking and vehicle states select different APRS symbols.
See [APRS_TRACKING.md](APRS_TRACKING.md) for the
wire format, receipt rules, privacy exclusions and live verification procedure.

## Provisioning scope

`scripts/prepare-t56.ps1` uses the shared guarded provisioning implementation to:

1. Select and verify an authorized `UNIPRO/ZX` target.
2. Report device identity without attempting serial rewrites.
3. Enable device-only GPS by default, or use `-RequestNetworkLocationConsent` and wait for the
   operator to accept Google's dialog before continuing. Provisioning verifies Google's stored
   consent value before requesting high-accuracy mode.
4. Verify/install-time or grant runtime microphone permission.
5. Verify the lab Wi-Fi profile or optionally provision it through the temporary helper.
6. Remove Zello for Android user 0 when approved; the system APK remains recoverable by OEM reset.
7. Launch Minimum once and verify/optionally assign its six-character Config Profile.
8. Validate and install a schema-3 private Last Known Good config with mode 600.
9. Request the Launcher3 recovery shortcut, verify no HOME chooser and open the appropriate
   Minimum activity.

Provisioning never clears Minimum app data, prints tokens/passwords or rewrites USB/Android serials.
