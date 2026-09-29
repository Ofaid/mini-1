# Development runbook

## Build and test

Run Gradle from `D:\mumla-dev`, not from the path containing a space, because the current NDK
toolchain has already shown path-sensitive behavior.

Clone with submodules. The parent uses GitLab Mumla history while the customized Humla commit is
published on the `humla-minimum` branch of `awatchar/minimum`:

```powershell
git clone --recurse-submodules https://github.com/awatchar/minimum.git
```

```powershell
Set-Location D:\mumla-dev
.\gradlew.bat :app:testFossDebugUnitTest :app:assembleFossDebug --no-daemon
```

APK output:

```text
D:\mumla-dev\app\build\outputs\apk\foss\debug\mumla-foss-debug.apk
```

Do not stage the pre-existing untracked `DEV_ENVIRONMENT_REQUIREMENTS.md` unless the user asks for
that separate file to be included.

## Admin portal development and handoff

The portal source is `web/`, a Next.js application deployed at
`https://minimum.vra.or.th/` with Vercel's **Next.js framework preset**. Keep the deployment on the
normal Next.js build output; do not add a standalone trace workaround for the Windows junction.

Vercel's project-level **Ignored Build Step** is set to **Only build production**. The production
branch is `main`; pushes to other branches are not expected to create Preview deployments. For a
requested Minimum WebUI change, branch/PR checks are an intermediate gate rather than delivery:
merge the reviewed change into `main`, wait for the resulting Vercel production deployment, and
smoke-check `https://minimum.vra.or.th/`. Skip merge/deployment only when the user explicitly asks
for local-only work.

Run the web checks from the build-safe junction:

```powershell
Set-Location D:\mumla-dev\web
pnpm install --frozen-lockfile
pnpm test
pnpm exec tsc --noEmit
pnpm build
```

For local UI work, `pnpm dev --hostname 127.0.0.1 --port 3010` may use the development-only memory
store. Production must fail closed unless Cloudflare KV is configured. Set these Vercel environment
variables server-side and never paste their values into a shell transcript or tracked file:
`CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_KV_NAMESPACE_ID`, optional
`CLOUDFLARE_KV_API_BASE` and `SESSION_SECRET` (32+ characters). BotID protection is enabled for production browser
mutations; do not disable it as a deployment workaround. Next.js 15.3+ initializes BotID from
`web/instrumentation-client.ts`. Dynamic BotID paths use `*` wildcards (for example
`/api/devices/*`), not Next.js `:deviceId` syntax; otherwise the client omits the verification
header and the server correctly rejects the mutation.

Production login rate limiting additionally requires a Cloudflare D1 database. Apply
`web/cloudflare/d1/0001_login_rate_limit.sql`, then configure `CLOUDFLARE_D1_DATABASE_ID`, a
least-privilege `CLOUDFLARE_D1_API_TOKEN` with D1 query access, and a stable
`LOGIN_RATE_LIMIT_KEY_SECRET` of at least 32 UTF-8 bytes. Keep these values server-side. Login
admission performs an atomic fixed-window D1 upsert per bucket: client first, then configured account
or a bounded decoy account only after the client passes. This ordering prevents an already-blocked
client from advancing the administrator lockout counter or consuming a KV administrator read. D1
timeout, configuration, network or response errors return a generic `503` and do not fall back to a
per-process counter. A blocked request returns `429` with `Retry-After`; successful logins consume
quota as attempts. Migration triggers prune rows expired for more than 24 hours.

The Vercel adapter uses Cloudflare's D1 REST control-plane API. This is acceptable for this
BotID-protected, low-volume private lab portal, but its latency and account-level API quota must be
monitored. Move admission behind a narrowly authenticated Cloudflare Worker with a D1 binding before
turning the portal into a public or high-volume service.

First-run production handoff:

1. Open `https://minimum.vra.or.th/` and create the administrator account when the `FIRST-RUN
   SETUP` screen appears. The password is stored as a one-way hash and the browser receives an
   eight-hour, HttpOnly, same-site admin session.
2. Register each radio's six-character Device ID in **Devices** and save its configuration. There is
   no token to copy, rotate or install on the handset.
3. Verify the Android client can fetch `GET /api/device-config/{deviceId}`. The legacy
   `/api/device/{deviceId}/config` path remains available for compatibility.

Read-only production smoke checks:

```powershell
curl.exe -sS https://minimum.vra.or.th/api/session
curl.exe -I https://minimum.vra.or.th/api/device-config/AB12C3
curl.exe -I https://minimum.vra.or.th/api/device/AB12C3/config
```

The expected pre-handoff state is `/api/session` `200` with `configured:false`. An unknown valid
Device ID returns `404`; a registered Device ID returns only that device's Schema-3 config.

## T99 ADB session

The current workstation ADB server uses port `5041` and the authorized T99 serial is `12344321`.

```powershell
$adb = "adb -P 5041 -s 12344321"
& adb -P 5041 devices
& adb -P 5041 -s 12344321 install -r D:\mumla-dev\app\build\outputs\apk\foss\debug\mumla-foss-debug.apk
& adb -P 5041 -s 12344321 shell am start -n se.lublin.mumla/.app.MumlaActivity
& adb -P 5041 -s 12344321 logcat -d -t 300
```

Avoid `pm clear` on the working T99 unless a fresh first-run test is explicitly needed; it removes
the device's current certificates, favourites and test state.

If `adb devices` hangs instead of returning an offline/unauthorized/device row, stop every `adb.exe`
process and retry ports 5041 and 5037. Confirm that Windows still reports the MI_03 `ADB Interface`
with problem code 0. A T99 power cycle does not repair a wedged Windows USB stack; if both the
installed platform-tools and a known-good isolated ADB exhibit the same hang, reboot Windows before
changing the handset, driver or app. Never factory-reset the radio for this host-side symptom.

For the reversible network-loss/recovery acceptance test, use the guarded script below. It restores
and reads back the exact prior Wi-Fi/mobile-data state in a `finally` block, refuses ambiguous
original settings, and never presses PTT:

```powershell
.\scripts\test-radio-reconnect.ps1 -WhatIf
.\scripts\test-radio-reconnect.ps1 -Force -OutageSeconds 30
```

The full fault matrix and the distinction between local audio handoff and server receipt are in
`docs/RECONNECT_TEST_PLAN.md`.

T99/T56/RYKS builds keep a bounded app-private hardware trace. It contains key metadata only and can be
read without exposing radio config or tokens:

```powershell
& adb -P 5041 -s 12344321 shell run-as se.lublin.mumla `
    cat files/radio-diagnostics/key-events.log
```

T99's verified PTT is F1/scan 59; F2 is physical EXIT and must never be configured as T99 PTT.
Clear the trace only when beginning a deliberate commissioning capture. Do not commit raw device
logs; copy only the sanitized mapping into the device profile.

For process-death acceptance, first confirm Ready and record the exact PID. Kill only that process
through the debuggable app UID; never use force-stop or clear app data:

```powershell
$minimumProcess = & adb -P 5041 -s 12344321 shell ps | Select-String 'se.lublin.mumla$'
$minimumPid = (($minimumProcess.ToString().Trim()) -split '\s+')[1]
& adb -P 5041 -s 12344321 shell run-as se.lublin.mumla kill -9 $minimumPid
```

T99's normal service restart may be deferred by roughly 16 minutes. The dedicated-radio watchdog
must instead produce a new PID within about 30 seconds, open RadioShell automatically and return to
`minimum-state-ready`. Do not issue `am start` after the kill because that invalidates the test.

The radio config directory has three durable states: `active-config.json` is the Last Known Good,
`pending-config.json` is waiting for an idle trial, and `previous-config.json` is the rollback copy.
Do not overwrite active merely to test an update. Stage pending, start `RadioShellActivity`, and
verify it connects and joins the selected room before expecting promotion. The pending-available
broadcast receiver is app-internal/non-exported, so an ADB `am broadcast` is intentionally not a
valid trigger; startup and the updater both invoke the safe path.

## Boot auto-start check

The receiver is enabled by default through `Settings.PREF_AUTO_START`. A valid simulated check is:

1. Launch the app once so Android has started the package normally.
2. Kill the app process without stopping the package.
3. Send `android.intent.action.BOOT_COMPLETED`.
4. On T99/T56/RYKS, check `dumpsys activity activities` for
   `se.lublin.mumla/.radio.RadioShellActivity`; generic Android retains
   `se.lublin.mumla/.app.MumlaActivity`.

Android may refuse the Activity launch on newer OEM builds. That is a platform limitation, not proof
that the receiver is missing; add a foreground-service/notification fallback before claiming broad
new-device support.

## One-shot provisioning for a known radio

Use the repository-root `Provision Minimum Device.cmd` for a factory-reset or newly received
T99/T56/RYKS. The normal operator double-clicks this file and does not enter PowerShell parameters. The
guided flow detects the active ADB port, explains how to authorize USB debugging, offers a numbered
device menu when several radios are attached, and shows recommended/custom setup choices. It keeps
the window open on PASS or failure so the result is not lost.

For a field workstation without a source checkout, download
`minimum-provisioning-<tag>.zip` from the GitHub Release, verify its `.sha256`, extract the complete
folder and double-click the launcher there. That bundle includes the signed APK and temporary Wi-Fi
helper, so Gradle and the project source are not required on the operator workstation.

Connect only one unit of a given model for the final reboot check. The workflow verifies the exact hardware,
builds the FOSS debug APK when requested or when the default APK is missing, installs it without
clearing app data, runs the guarded model preparation, opens the Portal, waits for the Device Profile
to become available, waits for `minimum-state-ready`, reboots, and waits for Ready again:

Double-click:

```text
Provision Minimum Device.cmd
```

Port `5037` is the Android standard and is selected when no ADB server is running. Port `5041` is
the existing Minimum lab alternative. The guided flow chooses the only port with an authorized
device automatically; if both servers are active or no device is visible, it presents a menu and
the USB-debugging/authorization checklist. Advanced automation may still call the underlying
PowerShell script with parameters, but field operators should use the launcher.

The script displays only the six-character Device ID and detected Portal model (`t99`, `t56` or
`ryks`). Register that ID under **Devices** at `https://minimum.vra.or.th/` with the displayed model
and save its configuration. The radio fetches by Device ID with no second enrollment credential.

For an unattended operator station, pre-create the Device Profile, then run:

```powershell
.\scripts\provision-minimum-device.ps1 -AdbPort 5041 -BuildApk `
    -DeviceProfile ABC123 `
    -NonInteractive
```

Use `-Serial` or `-TransportId` when more than one authorized ADB device is attached. Pass
`-SkipLabWifi` only when the unit has another verified network path; otherwise the existing ignored
DPAPI lab Wi-Fi credential is used. T56 network-assisted location remains an explicit operator
consent flow through `-RequestNetworkLocationConsent`.

Unknown manufacturer/model pairs are inventory-reported and rejected before APK installation or
provisioning changes. Complete physical button/PTT capture, add a guarded hardware profile and its
model preparation wrapper, and pass real-device acceptance before adding that model to this
one-shot path. Never treat the app's `generic-radio` fallback as hardware acceptance.

## T99 preparation and Zello removal

The canonical provisioning script is `scripts/prepare-t99.ps1`. The old
`scripts/remove-zello-t99.ps1` name remains as a compatibility wrapper. Preparation reports the
ADB serial, Android serial, USB gadget serial and Minimum app Device ID; it does not attempt a
root-only USB serial rewrite.

```powershell
Set-Location D:\mumla-dev
.\scripts\prepare-t99.ps1 -ReportOnly
.\scripts\prepare-t99.ps1 -WhatIf
.\scripts\prepare-t99.ps1
.\scripts\prepare-t99.ps1 -SkipMinimumHome
.\scripts\prepare-t99.ps1 -Force -DeviceProfile GYZ3DE `
    -RadioConfigPath C:\private\minimum-radio.json
```

The script also checks the lab SSID `..@EmergencyTU`. On a workstation's first use, create the
git-ignored DPAPI credential interactively; never put the password in a command, script or tracked
file:

```powershell
New-Item -ItemType Directory -Force .\scripts\.secrets | Out-Null
$labWifi = Get-Credential -UserName '..@EmergencyTU' `
    -Message 'Enter the Minimum T99 lab Wi-Fi password'
$labWifi | Export-Clixml .\scripts\.secrets\t99-lab-wifi.credential.xml
```

If the target is not connected, preparation builds and installs the standalone
`tools/t99-wifi-provisioner` helper, copies the credential through app-private storage, saves and
enables the WPA2 profile, then removes both helper and transient files. Use `-RefreshLabWifi` to
replace/recheck an existing profile or `-SkipLabWifi` to omit the step. The PSK is never printed or
committed. T99 physically passed profile refresh and automatic reconnection after a Wi-Fi radio
off/on cycle.

Provisioning enables Android Location by default: T99 uses high-accuracy GPS/network mode, while
T56 defaults to device-only GPS. To request T56 network location, run its provisioner with
`-RequestNetworkLocationConsent`; the script stops Minimum, resets consent through Google Services
Framework's own activity, opens its consent dialog and waits up to 120 seconds until the stored
`use_location_for_services` value changes from `0` to `1` after the operator accepts. Only then does
it request high-accuracy mode and wait for any additional Google network-location dialog to close.
It never presses or bypasses consent automatically, and restores GPS-only mode if consent is not
completed. Pass `-SkipLocation`
only for an explicit exception. Re-run the temporary redacted acceptance probe
with `scripts/test-radio-location.ps1 -Serial <adb-serial>`; coordinates are hidden unless
`-ShowCoordinates` is explicitly supplied. T56 has passed a roughly 5 m GPS fix, while T99 still
reports zero SNR/ephemeris and no fix. After operator consent, T56 also passed a redacted network
fix at roughly 29.21 m accuracy. Future tracking code must use
`RadioDeviceProfile.supportsLocationTracking(...)`; T99 and unverified generic hardware are denied.

If local PowerShell policy blocks scripts, run the same file with
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File`. Keep the supplied JSON outside the
repository: it may contain a public room token or a private server-certificate pin. The script
checks size, schema/config version, required Mumble/room fields and Device ID, then copies it to
app-private `files/radio-config/active-config.json` with directory mode 700 and file mode 600. It
does not display token values and removes the temporary ADB copy.

`-DeviceProfile` is the operator-facing name for the Technical Brief's six-character Device ID.
It assigns the stable `/devices/{deviceId}.json` lookup key after validating the same six-character
rules. It does not change the USB/ADB serial, hardware model profile or connection username. Config
schema 3 requires the latter explicitly per connection; the current T99 uses Config Profile `GYZ3DE` and Mumble
username `E25FGL-T99`.

This removes Zello for Android user 0. It does not erase the system APK from the read-only system
partition; a factory reset or OEM restore can make it reappear. If several identical T99 devices
show the same ADB serial, use `adb devices -l` and select the unique `transport_id`:

```powershell
.\scripts\prepare-t99.ps1 -TransportId 1
```

Without `-RadioConfigPath`, preparation opens `MinimumHomeActivity` after the Device ID check. It
provides two swipe pages: the large Minimum icon and Android Settings. With a config, it opens
`RadioShellActivity` so connection and room join can be verified immediately. T99 firmware does
not accept the data-installed Minimum
activity as a usable default HOME choice, so the app deliberately does not register as HOME. The
script requests a legacy Minimum shortcut in Launcher3, launches a real system HOME intent and
fails if ResolverActivity appears. At boot, T99/T56/RYKS profiles launch the radio client directly;
generic Android continues to launch MumlaActivity. Back from the radio client opens the recovery
dashboard instead of exiting to an uncertain launcher state.

`-SkipMinimumHome` skips shortcut/dashboard provisioning. Launcher3 is retained as an emergency
fallback and should show both Minimum and Settings after preparation.

## T56 preparation and capture procedure

T56 uses the default ADB port 5037 and must be selected by its verified `UNIPRO` manufacturer and
`ZX` model rather than by a committed serial. The wrapper delegates to the shared guarded T99
implementation after applying those identity checks:

```powershell
.\scripts\prepare-t56.ps1 -ReportOnly
.\scripts\prepare-t56.ps1 -WhatIf
.\scripts\prepare-t56.ps1 -Force
```

If this workstation currently owns both radios through the existing ADB daemon on port 5041, pass
`-AdbPort 5041`. T56 firmware does not include `/system/bin/run-as`; provisioning therefore uses
the app's `android.permission.DUMP`-protected receiver to report Device ID and import a validated
temporary config. The receiver accepts only `/data/local/tmp/minimum-radio-config-*.json`, and the
script removes that file immediately after the result is returned.

The captured T56 profile is `UNIPRO/ZX/L809`, Android 5.1.1/API 22, 160x128. Its PTT is vendor
keyCode 261; F1 is Menu and must never be copied from T99's PTT rule. The OEM keyguard consumes the
first raw event during display wake but simultaneously broadcasts `unipro.hotkey.ptt.down` and
`unipro.hotkey.ptt.up`. Minimum's manifest receiver forwards those T56-only actions to
`MumlaService`. Verify the receiver and the screen-off firmware actions with:

```powershell
adb -P 5041 -s <t56> shell dumpsys package se.lublin.mumla
adb -P 5041 -s <t56> logcat -d | Select-String 'unipro.hotkey.ptt'
```

The provisioner removes the obsolete experimental AccessibilityService component if an earlier lab
build left it enabled, while preserving unrelated accessibility services. Commissioning still
needs an operator physical retest of screen-off PTT after the OEM receiver build is installed. The
app-private trace remains useful for the two side keys and screen-power event; read
it with `run-as` as described for T99 when the target image provides that tool, sanitize it, and
update `docs/T56_DEVICE_PROFILE.md`. Never commit serials, certificates, tokens or personal Wi-Fi
details.

## RYKS preparation and capture procedure

RYKS is detected only as `ELINK/ym_258`. Its factory PackageManager requires the one-shot
provisioner to set and verify `ro.build.install=1` for the current boot before installing the APK.
The wrapper then uses the same guarded Zello, microphone, shortcut and HOME verification flow:

```powershell
.\scripts\prepare-ryks.ps1 -ReportOnly
.\scripts\prepare-ryks.ps1 -WhatIf
.\scripts\prepare-ryks.ps1 -Force -SkipLabWifi
```

The OEM framework maps both GPIO `CHAT` scans 216 and 249 to vendor keyCode 285 and emits
`com.zello.ptt.down/up`; both controls therefore remain PTT. The two keys below PTT are scan 66/F8
and scan 65/F7 for previous/next room. The three-line key is F2/scan 60 and toggles Device ID; red is
native POWER/scan 116 and retains the Power off / Reboot menu. Rotary volume remains Android-native.
See [RYKS_DEVICE_PROFILE.md](RYKS_DEVICE_PROFILE.md)
for the evidence and the remaining PTT/display-off physical acceptance.

## APRS tracking verification

The complete packet and privacy contract is [APRS_TRACKING.md](APRS_TRACKING.md). Use this bounded
procedure for a live check:

1. Build from `D:\mumla-dev` and install the FOSS debug APK. Do not build from
   `D:\VR Android App\mumla`; the current NDK is path-sensitive.
2. Confirm the target is the verified T56 profile, location consent is complete, and the device has
   an unobstructed sky view. Never enable tracking on T99; its immutable gate must remain disabled.
3. Clear only the `MinimumAprs` log buffer (`adb logcat -c`), start Minimum and wait for a quality-
   passing fix. PTT may evaluate a cached fix but must not be used to force GPS acquisition.
4. Inspect `adb logcat -d -s MinimumAprs:I MinimumAprs:W` for
   `APRS position accepted by send-only server`. A local write or a `204` without
   `X-Packetsrcvd > 0` is not acceptance.
5. On APRS.fi search `VR-*` and verify the new item is an Object, not a callsign station position;
   verify the state icon and compact Health fields. Use only redacted screenshots/log excerpts.
6. For isolation, check T99 with `dumpsys location` and `MinimumAprs` logs: no app location request,
   tracking manager or APRS send should exist.

Do not print or paste APRS passcodes, Mumble tokens, serials, Device IDs, SSIDs or exact coordinates.
Previously accepted callsign-owned packets cannot be removed from APRS-IS/APRS.fi history; they are
historical artifacts and must not be treated as the current implementation. HTTPS uses the pinned
ISRG Root X1 CA on API-22 and must retain normal hostname verification.

To change only a T56 APRS Object label without exporting its private config, install a build that
contains the protected provisioning action, then run:

```powershell
.\scripts\set-t56-aprs-object-name.ps1 -ObjectName T56-ROOF -StartRadioShell
```

The script verifies the `UNIPRO/ZX` target. The app validates/normalizes the label, increments
`configVersion` only when it changes, preserves credentials in app-private storage and keeps the old
active config as `previous-config.json`.

## Git workflow

Read [GITHUB_RELEASE_WORKFLOW.md](GITHUB_RELEASE_WORKFLOW.md) before triaging field Issues, marking
PR #1 ready, or publishing an APK. The repository CI and manual signed-release workflows do not
replace the physical T99/T56/RYKS acceptance gates.

```powershell
Set-Location D:\VR Android App\mumla
git status --short --branch
git diff --check
git add <only-intended-files>
git commit -m "<focused change>"
git push github agent/minimum-foundation
```

Keep `origin` pointing to GitLab upstream and use the `github` remote for this project. PR #1 is
draft; do not merge it automatically.
