# T99 device profile

ชื่อเล่นของเครื่องเป้าหมายในโครงการนี้คือ **T99** อุปกรณ์ในภาพเป็นเครื่อง Android แบบวิทยุ PTT OEM/white-label ที่ใช้แบรนด์ Motorola ในการตลาด ไม่ใช่ Motorola รุ่นมาตรฐาน จึงต้องถือว่า input และ USB behavior เป็น vendor-specific

## ข้อมูลที่ตรวจจากเครื่องจริง

| รายการ | ค่า |
|---|---|
| Android | 5.1.1 / API 22 |
| Manufacturer | Youdotech |
| Model | QM011 |
| SoC | Qualcomm MSM8909 |
| ABI | `armeabi-v7a` |
| RAM ที่ระบบรายงาน | ประมาณ 404 MiB |
| จอจริง | 132 × 132, density ประมาณ 114 |
| USB VID/PID | `05C6:9091` |
| USB functions | `diag,serial_smd,rmnet_qti_bam,adb` |
| MTP | ไม่ได้เปิดเป็น USB function ที่ตรวจพบ |
| ADB | ทำงานและ authorize แล้ว |

ข้อมูลระบุตัวเครื่อง เช่น serial, IMEI, MAC address, IP และ public key ไม่เก็บไว้ในเอกสารสาธารณะนี้

## GPS and location acceptance

T99 advertises `android.hardware.location.gps`, loads Qualcomm `gps.default.so`, runs the vendor
location processes and accepts a live `GPS_PROVIDER` fine-location request. This proves that the
Android framework and vendor GPS stack are present, but not that the RF path can produce a fix.

On 2026-08-05 a temporary standalone probe (not Minimum) requested GPS every second in two sessions
totalling more than five minutes while the radio was beside a window. The HAL reported up to 14
satellites from almanac data, but every reported SNR remained `0.0`, ephemeris count and used-in-fix
count remained zero, and no GPS location was delivered. The only cached fused location had roughly
440 km accuracy and is not GPS evidence. The temporary probe was removed after the test.

On 2026-08-07 a new two-minute probe after enabling high-accuracy Location again saw eight almanac
entries, zero SNR, zero ephemeris, zero satellites used and no GPS or network fix. GPS framework and
A-GPS capability flags are present, but real positioning remains **not accepted as working** on
this unit. `prepare-t99.ps1` now enables high-accuracy Location as requested, while deployment must
still treat T99 tracking as unavailable until an unobstructed open-sky fix passes. This is enforced
as a deny-by-default hardware capability in `RadioDeviceProfile.supportsLocationTracking(...)` and
recorded as `hardware.locationTrackingSupported=false` in the public T99 model profile.

## Lab Wi-Fi provisioning

`prepare-t99.ps1` manages the lab SSID `..@EmergencyTU` without placing its PSK in source control.
The password is stored in a git-ignored, Windows-user-bound DPAPI `PSCredential`. A temporary helper
APK reads a short-lived app-private request, saves/enables the WPA2 network through Android 5.1's
`WifiManager`, deletes the request and is immediately uninstalled. A physical refresh test passed,
and Wi-Fi disable/enable returned to the lab SSID automatically in approximately 5.3 seconds.

## Managed identity roles

The current device deliberately keeps these identities separate:

- Config Profile / six-character device lookup key: `GYZ3DE`
- Mumble username sent by Minimum: `E25FGL-T99`
- Operator's separate Mumble username (context only): `GY3ZDE`
- Hardware/model profile: `t99` / `t99-qm011`

The Config Profile selects `/devices/GYZ3DE.json`; it must never be silently reused as the Mumble
username.

## USB interfaces

Windows เห็น USB interface หลายตัวเป็นอุปกรณ์ชื่อ Android:

- `MI_00`: diagnostics
- `MI_01`: modem/serial
- `MI_02`: WWAN/application
- `MI_03`: ADB — ตัวนี้ใช้งานได้

อุปกรณ์สีเหลืองอีกสามตัวใน Device Manager จึงไม่ใช่ปัญหา ADB โดยตรง แต่เป็น driver ของ Qualcomm diagnostic/modem interfaces ที่ Windows ยังไม่มีหรือไม่จำเป็นต่อการควบคุมแอปผ่าน ADB

## Input inventory

ข้อมูลจาก `/proc/bus/input/devices` และ event inspection:

- `matrix_keypad.71`: `KEY_UP`, `KEY_DOWN`, `KEY_MENU`, `KEY_BACK`, `KEY_SELECT`
- `gpio-keys`: `KEY_F1`, `KEY_F2`, `KEY_VOLUMEUP`
- `qpnp_pon`: `KEY_VOLUMEDOWN`, `KEY_POWER`
- `msm8909-skua-snd-card Button Jack`: `KEY_VOLUMEDOWN`, `KEY_VOLUMEUP`, `KEY_MEDIA`, `BTN_4`, raw `0x0246`
- Headset Jack switch events

## PTT implications

Physical capture on 2026-08-05 proves that the labelled PTT button is raw `gpio-keys` F1, not F2
and not the Button Jack media path. Android delivered `KEYCODE_F1` (131), scanCode 59,
deviceId 4, source `0x00000101`, device `gpio-keys`, with normal DOWN/repeat/UP events. Minimum starts
TX only on repeat 0 and releases on UP. MediaSession keys remain safe alternate PTT inputs.

Physical EXIT is F2, so T99 explicitly rejects F2 as PTT even if a stale preference requests it.
The T99 application defaults overwrite the managed push key with F1 at every process startup.

## Verified ten-button map

| Physical label | Linux input | Android event | Input device | Minimum behavior |
|---|---|---|---|---|
| Power | `KEY_POWER` | `KEYCODE_POWER` / scan 116 | `qpnp_pon` | Android screen power |
| PTT | `KEY_F1` | `KEYCODE_F1` 131 / scan 59 | `gpio-keys` | PTT hold; F1 only on T99 |
| Volume + | `KEY_VOLUMEUP` | `KEYCODE_VOLUME_UP` 24 / scan 115 | `gpio-keys` | Android volume |
| Volume - | `KEY_VOLUMEDOWN` | `KEYCODE_VOLUME_DOWN` 25 / scan 114 | `qpnp_pon` | Android volume |
| MENU | `KEY_SELECT` | `KEYCODE_DPAD_CENTER` 23 / scan 353 | `matrix_keypad.71` | Hold 5 s for recovery dashboard |
| EXIT | `KEY_F2` | `KEYCODE_F2` 132 / scan 60 | `gpio-keys` | Hold 5 s for dashboard; never PTT |
| Up | `KEY_UP` | `KEYCODE_DPAD_UP` 19 / scan 103 | `matrix_keypad.71` | Hold 1 s: previous room and join |
| Down | `KEY_DOWN` | `KEYCODE_DPAD_DOWN` 20 / scan 108 | `matrix_keypad.71` | Hold 1 s: next room and join |
| Green | `KEY_MENU` | `KEYCODE_MENU` 82 / scan 139 | `matrix_keypad.71` | Short press confirms room; hold 1 s toggles full-screen Device ID |
| Red | `KEY_BACK`, scan 2 | vendor-remapped `KEYCODE_DPAD_RIGHT` 22 | `matrix_keypad.71` | Hold 5 s for recovery dashboard |

Activity diagnostics physically confirmed PTT, volume, direction and MENU metadata. EXIT/green
behavior was additionally checked with the captured kernel event, installed Android keylayout and
non-PTT ADB key injection. A controlled physical red-button capture on 2026-08-05 recorded
`matrix_keypad.71` scan 2 / Linux `KEY_BACK` for 6.91 seconds while the vendor WindowManager logged
Android `KEYCODE_DPAD_RIGHT` (22) repeats. The app-private bounded trace is
`files/radio-diagnostics/key-events.log`; it records no text, config, token or audio data.

The installed T99 build also passed deliberate-action checks: short EXIT/MENU/red presses remain in
RadioShell, a 5.4-second raw F2 hold opens MinimumHome, green reopens RadioShell, and a 1.2-second Up
hold completes the room action and returns to Ready. F1 received while MinimumHome is foreground
opens RadioShell and requests connection, but Android does not deliver arbitrary F1 events globally
when an unrelated application owns the foreground.

The first real F1-from-dashboard run exposed that Android can retarget the still-held press to the
new RadioShell window, which started TX despite the dashboard recovery policy. Minimum now starts a
service safety action before launching RadioShell, forces TX off, and requires a physical key-up
before any subsequent PTT DOWN is accepted. The corrected build is installed; physical retest is
still required.

Earlier app-private scan-60 `KEYCODE_BACK` traces were incorrectly attributed to the physical red
control. The isolated kernel and WindowManager capture above supersedes that claim: this T99's red
control reaches applications as `KEYCODE_DPAD_RIGHT`. Minimum classifies both DPAD_RIGHT and BACK as
protected T99 exit paths for compatibility, checks DOWN-to-UP duration on release, and records
DPAD_RIGHT in the bounded diagnostic trace. Physical acceptance passed after installing the fix:
the trace recorded both `activity` and `protected-exit` paths, the hold prompt remained visible,
and a hold longer than five seconds opened MinimumHome.

Managed Minimum profiles force the ordinary Mumla PTT confirmation click off at both preference and
runtime levels. The distinct failure tone for an offline, blocked or locally undeliverable PTT is
retained because it signals an operational fault.

## Software found on T99

พบ package `com.loudtalks` (Zello) version 5.9.1 / versionCode 2600751 ซึ่งมี component/filter ที่เกี่ยวข้องกับ PTT เช่น media button และ vendor-style PTT actions การมี filter ใน APK ไม่ใช่หลักฐานว่า firmware ของ T99 จะส่ง action เหล่านั้นให้แอปอื่น จึงใช้เป็นข้อมูลสำหรับ diagnostic และการทดลองเท่านั้น

## Development notes

### Launcher and boot behavior

- OEM HOME is `com.android.launcher3/.Launcher`; its factory workspace originally showed only
  Settings.
- Registering a data-installed app as HOME causes the API-22 resolver dialog, but the OEM resolver
  does not expose Minimum as a reliable selectable/default candidate on the 132x132 layout.
- Minimum therefore uses an explicit two-page radio dashboard, launched by `MumlaBootReceiver`, and
  installs a legacy Minimum shortcut into Launcher3 as the recovery path.
- Physical reboot verification passed: no `ResolverActivity`, dashboard focused after boot, and the
  Launcher3 fallback visibly contains both Minimum and Settings.
- The dashboard supports non-touch operation: DPAD up/left and down/right change pages; DPAD center,
  Enter, Button Select (`KEY_SELECT`), Call and T99 physical green (`KEY_MENU`) activate the visible
  page. On T99, F1 opens/reconnects Minimum from the dashboard and F2 is the labelled EXIT key; F2
  is never accepted as PTT.

- โปรเจคมี path build-safe `D:\mumla-dev` ซึ่งเป็น junction ไปยัง `D:\VR Android App\mumla` เดียวกัน
- Full FOSS debug build และติดตั้ง APK บน T99 สำเร็จแล้ว
- T99 `keyCode`, `scanCode`, action, repeat count and source-device capture is complete. Repeat the
  same capture for every new model rather than copying the T99 mapping. T56 has its own profile.
