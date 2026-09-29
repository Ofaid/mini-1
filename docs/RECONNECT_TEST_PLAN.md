# Minimum reconnect and TX-failure acceptance plan

Last reviewed: 2026-08-07

## Required behavior

For a provisioned managed radio, transport disconnects enter an indefinite retry loop. The delay
starts at 15 seconds, doubles to 30 seconds and caps at 60 seconds. Every connection path also
shares a persisted 15-second minimum interval, so network return, channel/server switching and
Android process recovery cannot bypass the throttle. A 60-second timer is the fallback for OEM
firmware that misses the connectivity broadcast. Successful Mumble synchronization resets the
backoff. Android process recovery uses redelivery of the validated connection intent.

Server reject, kick, ban, authentication failure and certificate-policy failure do not retry. The
full-screen UI remains in an error state until configuration, server policy or certificate trust is
corrected. This prevents invalid credentials from repeatedly consuming the Mumble IP autoban
budget.

## Acceptance scenarios

| Scenario | Injection | Expected evidence |
| --- | --- | --- |
| Wi-Fi/LTE loss | Disable every active transport for 20–60 seconds | Full-screen reconnecting state; no TX; throttled retry after transport returns; Ready after room join |
| Server TCP close/restart | Stop the disposable test listener or close the client socket | Repeated attempts continue through 15/30/60-second backoff and recover without operator input |
| DNS failure | Point a disposable candidate config at an unresolvable test name | Candidate fails and Last Known Good reconnects; active config is not replaced |
| Port closed | Use a disposable candidate with an unused port | Same Last Known Good rollback behavior; no false Ready state |
| Server reject/kick | Use a disposable test account/server policy | Full-screen error state; no automatic retry; record server reason without credentials |
| Network absent for hours | Leave transports unavailable for at least two hours | Service remains safe, fallback polling continues, and return to Ready requires no app restart |
| Android kills process | Kill the process without force-stopping the package | Watchdog lease opens RadioShell, a new PID appears and the radio reconnects/joins the configured room |
| Certificate changes | Present a certificate that mismatches the configured pin | No auto-trust, no retry storm, full-screen certificate safety hold |
| PTT while offline | Press the certified hardware PTT path while reconnecting | Failure tone once per press, no TX state and no stuck input |
| PTT from Minimum dashboard | Leave RadioShell through a deliberate five-second exit, then press physical PTT | Failure tone, RadioShell opens, connection starts immediately, current press is not queued, next press after Ready transmits |
| Accidental exit key | Tap the profile's protected exit keys, then hold each for five seconds in separate runs | Taps remain in RadioShell; each deliberate hold opens MinimumHome exactly once |
| Room hold action | Tap then hold Up/Down for one second with at least two configured rooms | Tap does nothing; hold changes and joins exactly one adjacent room without green confirmation |
| Encoder produces no packet | Deny/break audio only in a disposable test environment | Failure tone if no encoded packet is handed to the synchronized connection within 1.5 seconds |
| Disconnect during TX | Remove network while a supervised test transmission is active | TX releases immediately, watchdog work clears, playback unmutes and reconnect starts |

`scripts/test-radio-reconnect.ps1` automates the reversible network-loss case. It records Wi-Fi and
mobile-data state, disables both transports for a bounded interval, restores the exact prior state
in `finally`, verifies both settings after restoration, and waits for the stable ASCII accessibility
marker `minimum-state-ready`. It refuses to begin if either original transport setting is not an
unambiguous `0` or `1`. Because API-22 `uiautomator` returns no app nodes while the T99 display is
off, the verification loop wakes an off display before reading the marker; this does not launch a
new connection or inject PTT. It never presses PTT, mutates config, clears app data or prints tokens.

```powershell
Set-Location D:\mumla-dev
.\scripts\test-radio-reconnect.ps1 -WhatIf
.\scripts\test-radio-reconnect.ps1 -Force -OutageSeconds 30
```

## Meaning of the TX alert

Mumble voice packets do not provide an application-level per-packet delivery acknowledgement.
Minimum can prove that it was synchronized and handed an encoded packet to the Mumble connection;
it cannot prove from the handset alone that a remote listener heard it. Final acceptance therefore
requires a second client or server-side observer to monitor the test room. The warning implemented
in Minimum covers every locally detectable failure and must not be described as a server receipt.

Never inject a test transmission without an operator explicitly pressing PTT. Keep the supplied
access token and any certificate material outside logs, screenshots and this repository.

## T99 results on 2026-08-05

- A guarded 30-second Wi-Fi/LTE outage restored the original `1`/`1` transport settings and returned
  to `minimum-state-ready` without operator action.
- A baseline same-UID SIGKILL proved that T99 scheduled normal service redelivery roughly 16 minutes
  later, which is too slow for radio availability.
- With the renewable process watchdog installed, a same-UID SIGKILL changed the PID in 23.9 seconds
  and RadioShell returned to the configured Ready state in 30.9 seconds. No force-stop, app-data
  clear, manual Activity launch after the kill or PTT transmission was used.
- Short injected EXIT/MENU/red events remained in RadioShell; a raw F2 hold of 5.4 seconds opened
  MinimumHome and physical-green Android mapping reopened RadioShell. A raw Up hold of 1.2 seconds
  completed the room action and returned to Ready. No PTT event was injected.
- A stopped-process recovery simulation launched RadioShell with the same explicit intent used by
  the dashboard/media recovery path and reached `minimum-state-ready` in one second without TX.
- The first physical dashboard-F1 run revealed a cross-window key race and incorrectly started the
  TX timer. The hotfix arms a service/Activity release-required lock before the launch, force-stops
  any TX and waits for key-up. Do not mark the scenario PASS until the physical retest confirms that
  the first press only opens/alerts and a fresh post-Ready press is the first one that can TX.
