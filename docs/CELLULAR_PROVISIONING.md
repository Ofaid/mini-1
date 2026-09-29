# Managed cellular policy

The T56 provisioning workflow applies a guarded cellular policy before its final connectivity
checks and the one-shot provisioner verifies it again after reboot. The policy is deliberately
limited to the commissioned `UNIPRO/ZX`, Android API 22, build `T56`, L811 modem family. Unknown
hardware or firmware is rejected before any cellular setting can be changed.

## Policy and cost warning

- Data Roaming defaults to enabled. Roaming can incur carrier charges.
- Pass `-DisableDataRoaming` to `prepare-t56.ps1` or `provision-minimum-device.ps1` when the SIM
  agreement prohibits roaming. The opt-out writes and verifies the disabled value.
- Mobile data is enabled and read back.
- The commissioned LTE-capable automatic mode with legacy fallback is preserved. LTE-only and
  unknown modes are unsafe. Android API 22's `Settings.Global` database does not prove that a modem
  accepted a preferred-mode write, so this workflow does not rewrite the numeric mode or claim it
  was applied. The accepted T56 reports symbolic
  `LTE/TDSCDMA/CDMA/EVDO/GSM/WCDMA automatic` (OEM value 22); any other mode is `WARN` and requires a
  separately verified OEM/telephony control path. Never copy that numeric value to another build.
- `manage-cellular.ps1 -VerifyOnly` makes no change and is suitable for post-reboot checks.

The report contains model/build, symbolic radio mode, SIM readiness, service state, voice/data RAT,
roaming state, data state, sanitized signal value/source, and only the status of the selected APN.
It does not query or print IMEI, IMSI, ICCID, phone number, APN name, APN credentials, or exact cell
identity. The script requests only the non-secret preferred-APN row identifier. The shell cannot
read even that projection on the commissioned firmware, so access unavailable is a distinct
warning rather than reading or guessing APN fields.

## Outcomes and bounded recovery

`PASS` means the setting readbacks, SIM, registration, safe preferred mode, mobile-data policy, APN
status, and cellular route were verifiable. `WARN` accepts registered 3G/2G fallback, an inactive
cellular route while another transport is active, an unsafe/unverifiable preferred-mode boundary,
or OEM-restricted APN inspection. `FAIL` covers a SIM that is not ready, a roaming/mobile-data
readback mismatch, disabled mobile data, or missing cellular service.

If registration is stale, perform at most one controlled airplane-mode re-registration or reboot,
then run the verifier again. Do not loop, force LTE-only, overwrite carrier APNs, clear Minimum app
data, or change device/subscriber identity. Carrier APN changes and band/entitlement claims require
operator documentation or sanitized modem evidence.

Rollback is explicit: rerun with `-DisableDataRoaming` if roaming must be off. Restore a previously
recorded preferred-network mode only when its symbolic meaning is verified for the exact firmware;
do not restore LTE-only or an unknown numeric mode.

## T56 acceptance record (sanitized, 2026-08-13)

- Hardware/software: UNIPRO/ZX, Android 5.1.1/API 22, build T56, baseband
  `LANSUS1-L811V0.00.01`; installed Minimum was an `e3657bf7` debug descendant before update.
- SIM/operator: SIM ready, TRUE-H; SIM product class and entitlement were not exposed by Android
  shell and therefore remain unknown. No subscriber or cell identifiers were recorded.
- Initial state: Data Roaming `1`, mobile data `1`, automatic LTE-plus-legacy mode `22`, home/not
  roaming, Android voice/data RAT both LTE, RSRP about -98 dBm. The cellular route was initially
  inactive with framework reason `dataDisabled`, despite the mobile-data setting readback.
- One controlled airplane-mode re-registration briefly restored a connected cellular route. The
  device remained registered on LTE and reported about -100 dBm RSRP afterward. Following the
  single acceptance reboot, the route returned to `dataDisabled` even with global and per-default-
  subscription mobile-data settings enabled; the default-data subscription was valid and Android
  still reported data as possible. One 45-second Wi-Fi-off probe did not recover the route, and the
  original Wi-Fi state was restored exactly. Bounded recovery was stopped there. This establishes
  that the old 3G symptom is not currently reproducible and that LTE registration works in the test
  area, but usable cellular data remains `WARN` pending carrier/framework APN or entitlement
  diagnosis. It does not prove why an earlier session stayed on 3G.
- Selected APN inspection is denied to the ADB shell by this OEM provider. The provisioner therefore
  returns `WARN`, suppresses APN identity/credentials, and does not overwrite carrier APNs.
- Roaming/network mode readback was idempotent before and after re-registration. Reboot persistence
  is recorded separately with the install candidate because the same reboot validates the updated
  telemetry behavior without adding an unnecessary second reboot.
