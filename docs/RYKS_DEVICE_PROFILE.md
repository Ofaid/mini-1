# RYKS device profile

สถานะเอกสาร: commissioning profile สำหรับเครื่อง RYKS ที่ตรวจเมื่อ 2026-08-12

## Identity and platform

- Android build identity: manufacturer `ELINK`, model `ym_258`, device `ELINK`
- Android 8.1.0 / API 27
- Display: 160 × 128 px at 120 dpi
- Minimum hardware/model profile: `ryks` / `ryks-elink-ym-258`
- Location tracking remains disabled until a separate real-device acceptance is completed.

Do not commit the unit's ADB serial, Minimum Device ID, private configuration, room token or server
certificate data. `scripts/prepare-ryks.ps1` selects the stable manufacturer/model pair and delegates
to the guarded shared provisioner.

## Input inventory

The GPIO/rotary and labelled-key rows below include the operator capture from 2026-08-12:

| Control path | Linux scan/code | Android path | Minimum behavior |
|---|---:|---|---|
| `gpio_keys/ptt_btn1` | 216 / `KEY_CHAT` | vendor keyCode 285 plus `com.zello.ptt.down/up` | hold-to-talk PTT |
| `gpio_keys/ptt_btn2` | 249 / `KEY_CHAT` | vendor keyCode 285 plus the same OEM broadcasts | secondary hold-to-talk PTT |
| upper key below PTT | 66 / `KEY_F8` | `KEYCODE_F8` 138 | hold 1 s to select/join the previous configured room |
| lower key below PTT | 65 / `KEY_F7` | `KEYCODE_F7` 137 | hold 1 s to select/join the next configured room |
| rotary clockwise | 115 / `KEY_VOLUMEUP` | Android volume up | raise media/playback volume |
| rotary counter-clockwise | 114 / `KEY_VOLUMEDOWN` | Android volume down | lower media/playback volume |
| green | 353 / `KEY_OK` | `KEYCODE_DPAD_CENTER` 23 | confirm/rejoin selected room |
| three-line menu | 60 / `KEY_F2` | `KEYCODE_F2` 132 | press once to show/hide Device ID |
| red | 116 / `KEY_POWER` | system Power policy | long press keeps the native Power off / Reboot menu |

The two `CHAT` GPIOs must remain PTT. The OEM PhoneWindowManager broadcasts the same action for
both and does not include a scan-code extra, so assigning scan 249 to channel selection would allow
an unintended transmission. Minimum therefore uses scans 66/65 for previous/next room actions.

The rotary and red Power key stay on Android's native paths. Minimum consumes the physical F2
three-line key so it no longer falls through to Settings while a Minimum screen is active.

## Screen-off PTT path

The firmware reports `ro.build.ptt_type=ANYPTT`. Its PhoneWindowManager maps vendor keyCode 285 to:

- `com.zello.ptt.down`
- `com.zello.ptt.up`

The running foreground `MumlaService` registers `RadioHardwareKeyReceiver` at runtime for these
RYKS-only actions. This is required because Android 8.1 suppresses the equivalent manifest receiver
before application code runs while the display is off. The runtime receiver delivers each edge
directly to the existing service-owned room-ready gate, release handling, disconnect safety and
120-second watchdog; it never queues a press for later transmission. When DOWN starts while the
display is off, RadioShell reads the service-owned talking state as it resumes so it does not
incorrectly render Ready during TX.

An injected keyCode-285 test first proved that PhoneWindowManager emits both OEM actions. A later
physical hold started from an explicitly verified `Asleep` / display-OFF state and produced the OEM
DOWN and UP about 3.9 seconds apart while waking RadioShell. Device logging then proved Android was
suppressing the static receiver. After installing the runtime-receiver build, a second physical hold
from display OFF transmitted successfully, its DOWN/UP deliveries completed in single-digit
milliseconds, and RadioShell returned to Ready after release without a stuck TX state.

## Installation and provisioning

The factory PackageManager rejects third-party installs while `ro.build.install` is absent or `0`.
On this debuggable image the shell can set the absent read-only property once per boot:

```powershell
adb shell setprop ro.build.install 1
adb install -r minimum-foss.apk
```

The one-shot provisioner performs and verifies this step only after detecting `ELINK/ym_258`.
`prepare-ryks.ps1` then grants microphone permission, removes Zello for user 0 while retaining the
read-only system APK, requests the Minimum recovery shortcut and verifies that Android HOME does
not show a chooser. The property resets with a reboot and is not written to the system partition.

The portal registration is Device-ID-only. After the Device Profile exists, Android fetches at
every process start plus the normal six-hour/network-return triggers; no bearer token is issued or
copied to the radio. Physical acceptance on 2026-08-12 installed the tokenless APK, activated portal
Config v12 and rebooted successfully back to RadioShell Ready with saved Wi-Fi reconnected and
`pending=false`.

Android High Accuracy (`gps,network`) was enabled and survived reboot. The initial 120-second
redacted probe detected 15 satellite entries but no ephemeris/SNR/used-in-fix and no GPS/network
fix. A repeat probe reported GPS enabled, network provider unavailable, 16 almanac entries and again
zero ephemeris/SNR/used-in-fix or coordinates. These runs justify keeping RYKS APRS disabled, but
they do not prove permanent hardware incapability because neither run was a controlled ten-minute
open-sky acceptance test.

## Web configuration

The portal model value is `ryks`. Its model-owned hardware data includes vendor PTT keyCode 285,
PTT scans 216/249, F8/F7 scans 66/65, F2 menu scan 60, DPAD_CENTER green scan 353, native Power
scan 116 and `locationTrackingSupported=false`.
Selecting RYKS in the portal preserves connections/channels while applying these model values and
disabling tracking/APRS.

## Remaining physical acceptance

- Verify real PTT DOWN/UP while RadioShell is Ready, first with display on and then after confirming
  `Display Power: state=OFF`.
- Confirm both PTT GPIOs release TX normally and do not leave a stuck state.
- Verify F8/F7 room selection with at least two live configured rooms.
- Reconfirm green and rotary volume. The corrected build has physically received repeated
  three-line F2 DOWN/UP events through the Device ID toggle path without leaving RadioShell; the
  operator-approved red long press remains the native Power menu.
