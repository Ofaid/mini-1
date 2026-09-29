# Minimum existing-device updater

`Update Minimum Device.cmd` is for a supported radio that already has Minimum identity and an
active managed configuration. Use `Provision Minimum Device.cmd` for a factory-reset, new or
unregistered radio. The updater never registers a device in the Portal, reprovisions Wi-Fi,
removes OEM apps, reopens Location consent or reapplies unrelated device settings.

## Requirements and trust boundary

- Windows 10 or 11, Android Platform Tools (`adb.exe`), and Android Build Tools `apksigner`.
  The updater finds `apksigner` in `PATH`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or the standard local
  Android SDK. This fail-closed dependency is required for full APK signature verification.
- One complete, extracted `minimum-provisioning-<tag>.zip` from a reviewed GitHub Release.
- The separately published ZIP `.sha256` must match before extraction. This external checksum is
  the pre-extraction trust anchor for every bundled file, including the updater itself; an
  in-bundle verifier cannot authenticate itself. After that comparison passes, the updater checks
  the exact file allowlist and hashes in `RELEASE-MANIFEST.json`, the APK checksum file, binary APK
  package/version, and APK signer for internal consistency and post-extraction tampering. This is
  repeatable on an operator's existing workstation; a freshly installed or otherwise "clean"
  workstation is not required.
- USB debugging must be enabled and authorized. The normal path accepts exactly one device in the
  Android `device` state. Offline, unauthorized, recovery, unknown and ambiguous targets stop
  before mutation.

No source checkout, Gradle, Android Studio, signing key, Portal login or app-data export is used.
Do not use a bundle whose ZIP checksum does not match the checksum on its exact Release.

## Normal update

1. Verify the downloaded ZIP SHA-256, then extract the entire ZIP.
2. Connect one already-provisioned supported radio and authorize USB debugging.
3. Ensure it is not transmitting, then double-click `Update Minimum Device.cmd`.
4. Type `UPDATE` only after checking the physical radio is not transmitting.
5. Keep it connected until `PASS` or an actionable `FAIL` appears.

The updater inventories battery/power, supported model, installed version, Device ID, managed
configuration and Ready state. It requires the installed APK and bundled APK each to have exactly
the one reviewed signing certificate before any installation; extra signers fail closed. It rejects an unintended downgrade. It uses only an in-place
`adb install -r` (or explicitly authorized `-r -d`) and contains no uninstall or clear-data path.

`PASS` means the exact target package/version is installed and the original Device ID, non-pending
managed configuration, last-known-good evidence and Ready state were verified. If the manifest
requires reboot, or `-FullRebootAcceptance` is requested, PASS also requires the same supported
profile and Device ID to return to Ready after reboot. `ALREADY_OK` is a successful idempotent
recheck of an already-installed exact version.

For `3.7.3-minimum.2` / versionCode `3070301`, an upgrade from versionCode `3070300` or older on
T56 also runs the exact reversible `CELLULAR_POLICY_V1_T56` migration before APK replacement. It
applies the guarded roaming/mobile-data/automatic-LTE policy, then requires a reboot and verifies
the same policy again. A carrier/APN readiness warning produces overall `WARN`, not a false PASS.
T99 and RYKS skip this model-gated migration.

The `3070300` receiver exposes only the five-field legacy status and its legacy actions can create
identity. For this single `3070300`-or-older to `3070301` bridge, the updater first uses Android's
read-only `run-as` boundary to extract only the existing six-character public identity from the
debuggable app's private preferences, without invoking application code or printing the preference
file. If `run-as` is unavailable, it does not start Minimum. Instead, the operator must already have
woken/unlocked the radio and manually opened the existing RadioShell Ready screen. The updater then
requires that exact activity to be focused and a fresh UI hierarchy to contain package-bound
`minimum-state-ready`; it records proof mode `LEGACY_READY_UI` and a safe `Channel <name>` baseline
when present. The temporary device-side hierarchy is removed immediately and its raw XML is never
saved in a report. Wrong-package, unfocused, screen-off or non-Ready evidence fails with
`LEGACY_NONCREATING_PROBE_UNAVAILABLE` before any receiver. After either proof, status must also
prove a non-pending active LKG and the legacy status/identity results must match each other (and the
private ID when `run-as` supplied one).
Selected channel, LKG digest and safe-settings digest are
recorded as `UNAVAILABLE_LEGACY`; after installation the new expanded report is mandatory and those
fields are marked `BOOTSTRAPPED_POST_UPDATE`, not falsely claimed as pre/post preservation proof.
Subsequent updates use the expanded report and require exact preservation of all reported fields.

## Safe advanced modes

From PowerShell, optional modes include:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-minimum-device.ps1 -ReportOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-minimum-device.ps1 -WhatIf
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-minimum-device.ps1 -UpdateSession
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\update-minimum-device.ps1 -FullRebootAcceptance
```

`-UpdateSession` handles one physical radio at a time, requires disconnection before continuing,
retains earlier results after a later failure, and prints totals. Use `-Serial` or `-TransportId`
only for an intentional advanced selection. Reports are written under the current user's local app
data by default; they include Device ID for fleet correlation but exclude Android/USB serials,
subscriber identifiers, credentials, certificate fingerprints, private coordinates, app data and
logs.

Downgrade is intentionally refused unless `-AllowDowngrade` is supplied. That switch produces a
prominent warning and does not bypass signer verification, change identity, uninstall Minimum or
clear data. Use it only with an explicitly reviewed recovery plan. The report states that rollback
is not automated; the old APK is not included in the bundle.

## Failure and recovery

- `SIGNER_MISMATCH`: stop. A debug-signed APK cannot be replaced in place by a differently signed
  Release APK. The updater has made no uninstall/data-clear attempt. Preserve the device and seek an
  explicitly reviewed migration decision.
- `DOWNGRADE_REFUSED`: obtain the correct newer reviewed bundle; do not bypass the gate casually.
- `BUNDLE_*`, `MANIFEST_*`, `APK_*`: discard the extraction, re-download the exact Release, verify
  its published ZIP checksum and retry.
- `INSUFFICIENT_STORAGE`: free non-Minimum storage and rerun. Do not clear Minimum data.
- `IDENTITY_UNREADABLE` or `CONFIG_UNVERIFIED`: do not update. Relaunch the existing app, restore
  connectivity if safe, and use the sanitized report for diagnosis.
- `LEGACY_NONCREATING_PROBE_UNAVAILABLE`, `LEGACY_NOT_PROVISIONED` or `LEGACY_BRIDGE_UNSUPPORTED`:
  no receiver or install was allowed when the non-creating probe failed;
  wake/unlock and manually open the existing Ready RadioShell, or provision the radio through the
  reviewed provisioning path. The updater never starts the app to manufacture this evidence.
- `READY_TIMEOUT`, `BOOT_TIMEOUT`, `REBOOT_TARGET_AMBIGUOUS`: keep the intended unit isolated,
  restore USB authorization/connectivity and rerun. A successful install alone is never PASS.
- Interrupted USB: reconnect the same radio and rerun. Verification and migrations are designed to
  report `ALREADY_OK` where the intended state is already present.

Attach the generated `.txt` and matching `.json` report when requesting help. Never attach a full
bugreport, app-data backup, raw `dumpsys`, or unsanitized ADB log.

## Migration and physical-acceptance policy

Migration entries are keyed by installed/target version and supported model. An unknown manifest
migration is refused; it is not silently skipped. The reviewed `CELLULAR_POLICY_V1_T56` mapping is
accepted only with `fromVersionCodeMax=3070300`, `toVersionCode=3070301`, profile `T56`,
`rebootRequired=true` and `irreversible=false`. Its helper is idempotent and firmware-gated to the
accepted UNIPRO/ZX API-22 T56/L811 combination. See `CELLULAR-README.md` for its cost warning,
sanitized evidence, documented readiness limitation and rollback boundary.

The known E7ROW7 T56 has a debug-signed build. Do not try to install a Release-signed APK on it.
Physical updater acceptance without reset must instead use two reviewed APK versions signed by the
same debug key: record same ID/config/Ready on version A, update in place to version B, verify the
same ID/config/Ready, optionally reboot for same-ID Ready, then rerun version B to prove
`ALREADY_OK`. This is debug-channel updater evidence only; it does not prove Release-signer
acceptance or authorize a data-destructive signing-key transition.
