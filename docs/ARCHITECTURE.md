# Minimum architecture

## Current component map

```text
MumlaApplication
  ├─ creates persistent Device ID
  └─ applies normal Mumla theme/locale

MumlaActivity (main radio client)
  ├─ normal Mumla navigation and server database
  ├─ automatic first-run certificate generation
  └─ foreground service binding

MumlaService (long-lived voice path)
  ├─ Humla/Mumble connection, TLS, Opus and audio
  ├─ MediaSession PTT bridge
  ├─ configured Activity key bridge
  ├─ service-owned remote RX session tracker
  ├─ indefinite managed-radio reconnect/backoff and process recovery
  ├─ bounded display wake plus local PTT-delivery warning
  └─ PTT watchdog and safety release

RadioConfigRepository
  ├─ embedded safe default
  ├─ Device-ID-addressed complete config from minimum.vra.or.th
  ├─ API-22 TLS 1.2 plus platform/ISRG Root X1 trust
  ├─ schema and safety validation
  └─ downgrade-protected pending/active/previous private cache

AccessTokenResolver
  ├─ reads public tokens from the selected channel only
  ├─ trims and de-duplicates in first-seen order
  └─ excludes protected references until secure storage exists

MinimumHomeActivity (radio dashboard, not Android HOME)
  ├── one large Minimum icon per page
  ├── swipe: Minimum -> Settings
  └── tap: open the selected recovery/action target

RadioLauncherShortcutInstaller
  ├── requests a legacy Minimum shortcut in Launcher3
  └── runs at app startup and through the provisioning receiver

MumlaBootReceiver
  ├── T99/T56 -> RadioShellActivity
  └── generic Android -> MumlaActivity

RadioShellActivity (managed radio entry point)
  ├─ hardware-first connection/RX/TX state display
  ├─ prominent channel alias and multi-talker display
  └─ full-screen Device ID overlay from verified hardware holds
```

The component map above preserves the original Mumla entry points for diagnostics. The managed
radio path is now `MumlaBootReceiver -> RadioShellActivity -> MumlaService`; T99/T56 no longer boot
to the recovery dashboard. `RadioConnectionConfig` provides the typed validated subset consumed by
the shell, and `RoomPathResolver` performs exact full-path channel lookup. `MinimumHomeActivity`
remains a deliberate recovery route to Minimum and Android Settings.

The production configuration control plane is the Next.js administrator portal at
`minimum.vra.or.th`. It keeps administrator sessions, pending-device registrations, device records
and canonical Schema-3 model templates, persists them in Cloudflare KV, and serves a complete
device-specific config through a Device-ID lookup endpoint. The portal advances
`configVersion` for effective changes; Android remains responsible for operational Last Known Good
trial and promotion.

On T99, the recovery dashboard consumes F1 by sounding a local failure alert and opening
RadioShell with an explicit connection request; it never queues that press for later TX. RadioShell
requires a one-second Up/Down hold to select and join a room, and requires a five-second hold on
physical MENU/EXIT/red before returning to the dashboard.

## Configuration source and precedence

Managed devices fetch one complete device-specific Schema-3 document from
`https://minimum.vra.or.th/api/device-config/{deviceId}` with no second credential-copy step. The portal
normalizes its model template and device record before serving it; Android no longer fetches and
merges the public GitHub Pages default/model/device files. The checked-in Pages tree remains a
non-secret reference/recovery artifact. If the Device ID is not registered or the network is
unavailable, startup still has the embedded safe default or Last Known Good cache.

A lower `configVersion` cannot replace a valid newer active config, and changed content at the same
version is rejected. Portal normalization must preserve an existing keyed `connections` map as a
collection; it must not reinsert the placeholder `public-main` connection into a managed record.

```text
managed response validation
  -> pending-config.json
  -> wait until connection transition, RX and TX are idle
  -> trial candidate in memory
  -> connect and join the configured room
     -> success: active becomes previous; pending becomes active
     -> failure: discard pending; reconnect with active Last Known Good
```

Startup reads `active-config.json`. If it is corrupt and `previous-config.json` is valid, the
repository restores previous; otherwise it uses the embedded safe default. Network return forces a
new fetch, while an in-flight guard prevents overlapping refreshes.

Channel selection has two layers. The app-private Last Selected Channel wins across Activity,
process and connection recovery. `radio.defaultChannel`, edited explicitly in the portal's
**Channels & default** tab, is used only when that selection is absent or references a removed
channel. Changing the default never silently replaces a valid saved selection.

## Radio connection and trust flow

```text
validated Last Known Good config
  -> ensure the existing Mumla client certificate
  -> resolve public access tokens without logging or database persistence
  -> connect through ServerConnectTask and MumlaService
  -> if normal TLS rejects the certificate, follow the validated config trust policy
  -> auto-trust and persist the presented leaf, or require the optional exact SHA-256 pin
  -> after ServerSync, resolve and join the exact full room path
```

Publicly trusted servers continue to use normal Android trust. For a configured self-signed server,
connection-level `autoTrustServerCertificate` defaults to true and stores the presented leaf in the existing
app-private BKS trust store before retrying. An optional SHA-256 pin overrides this permissive mode
and must match exactly. Trust is scoped to the app store and configured endpoint workflow; no
system CA store or global hostname verifier is changed.

## PTT lifecycle

```text
physical/media/touch down
  -> MumlaService.onTalkKeyDown()
  -> connected + PTT mode check
  -> TX starts + 120 s watchdog

physical/media/touch up
  -> MumlaService.onTalkKeyUp()
  -> TX stops and watchdog is cancelled

disconnect / service destroy / network loss
  -> releasePttForSafety()
  -> TX stops; a held key must be released before a new TX
```

The service owns the safety behavior. UI surfaces must call the service and must not implement a
second independent PTT timer.

An offline PTT press is intentionally not queued. Minimum sounds/shows failure, opens the radio UI
when the dashboard or MediaSession path receives the event, and requests connection immediately.
The user presses PTT again after Ready. This avoids a stuck transmission when key-up belongs to the
old window or is lost during process/activity recovery. `MumlaService` additionally gates every
managed-radio PTT source on Mumble synchronization, PTT mode and verified entry into the configured
room. Standard Android cannot globally capture T99's arbitrary F1 key while an unrelated app is
foreground; that requires a separately verified OEM broadcast, privileged input component or
provisioning-time keylayout remap.

Managed radios also enable Speex input preprocessing and half-duplex automatically. Half-duplex
mutes playback only while TX is active and explicitly unmutes during audio teardown, including a
disconnect during TX. The UI timer is display-only; the 120-second safety watchdog remains owned by
the service.

Humla observer fan-out uses an unordered concurrent set. `RadioShellActivity` therefore defers and
coalesces RX rendering until the current callback fan-out has completed, ensuring
`MumlaService` has updated `RadioReceiveTracker` first. This prevents a Ready flash before the
speaker name, guarantees the final remote PASSIVE/removal event returns the UI to Ready, and keeps
pending-config idle checks aligned with the same service-owned snapshot.
Room-join completion uses this same refresh path so it cannot overwrite an already-active RX state
with Ready.

The service exposes a complete ordered snapshot of all active remote talkers. `RadioTalkerDisplay`
formats that snapshot without discarding simultaneous speakers: compact T99-class displays reserve
two lines, while larger displays reserve four. When the snapshot exceeds the visible line budget,
the final visible line becomes `+N` while the full ordered list remains available through the
view's accessibility content description. Fixed line counts keep RX state changes from resizing
the shell.

RadioShell keeps connection identity separate from presentation identity. `channels[].path` remains
the exact Mumble room target, while optional `channels[].alias` is rendered in a persistent amber
`CHANNEL` badge below the traffic state. The badge is larger than the former path text and uses a
monospace bold style distinct from talker names. Legacy configs fall back to `channels[].label`; a
join callback must never replace the badge with the server's full path.

## Managed-radio reconnect lifecycle

```text
unexpected disconnect
  -> release TX and unmute playback
  -> wake full-screen reconnect status
  -> retry transport errors after 15/30/60 seconds (cap at 60 seconds)
  -> enforce at least 15 seconds between every managed-radio connection attempt
  -> persist the attempt guard across Android service-process restart
  -> no network: wait for connectivity event plus 60-second fallback poll; the guard still applies
  -> server reject/kick/ban/auth failure: stop retrying and show the failure state
  -> synchronized: reset backoff and join configured room
```

The Android service returns `START_REDELIVER_INTENT` only for the managed-radio connection mode so
process recovery receives the same validated connection intent. Certificate policy/pin mismatch is
the deliberate exception: it cancels retry and remains visibly fail-closed.

This policy is intentionally conservative because Mumble server autoban is IP-based. Upstream
defaults are 10 attempts in 120 seconds, a 300-second ban, and successful connections included in
the counter; the implementation bans when the count exceeds the limit, so the default threshold is
crossed on the 11th attempt inside the window. Server operators may configure stricter values.
Sources: [mumble-server.ini](https://github.com/mumble-voip/mumble/blob/master/auxiliary_files/mumble-server.ini)
and [Meta.cpp](https://github.com/mumble-voip/mumble/blob/master/src/murmur/Meta.cpp).

T99 firmware can nevertheless apply an OEM service-restart backoff of roughly 16 minutes after a
process kill. Dedicated T99/T56 profiles therefore maintain a process-independent AlarmManager
lease. A healthy service refreshes a 30-second lease every 10 seconds, so the alarm never fires in
normal operation. If the process dies, the retained alarm starts
`RadioProcessWatchdogReceiver`; it re-arms itself and opens `RadioShellActivity`. The receiver keeps
retrying if a background launch is blocked, while generic Android profiles do not arm this lease.

## Hardware strategy

`RadioDeviceProfile` identifies T99, T56 or generic hardware. The profile only selects a config
namespace. Actual keycode/scancode mappings remain data-driven because cheap radio firmware often
exposes the same physical button through different Linux input devices.

The six-character `DeviceIdentityManager` value is also the externally provisioned Config Profile
and selects `/devices/{deviceId}.json`. It is not the Mumble login. Config schema 3 stores login and
server policy in keyed `connections`; each selectable channel references one connection and owns
its access tokens. The current T99 mapping is Config Profile `GYZ3DE` and connection username
`E25FGL-T99`; the hardware profile remains `t99`/`t99-qm011`.

Location tracking is deny-by-default at the immutable hardware layer. `AprsTrackingManager` is
created only when `RadioDeviceProfile.supportsLocationTracking(...)` accepts T56; T99 and generic
hardware return false regardless of remotely supplied config metadata. T56's coordinator rejects
stale/poor fixes, suppresses GNSS jitter, and feeds SmartBeacon, PTT and retry events through one
semantic duplicate gate. Stationary acquisition uses a short-lived regular GPS request plus a
90-second window, then sleeps until the 30-minute poll; walking/vehicle sampling is more frequent
but beacon cadence never falls below one minute. APRS sends a timestamped Object report using the
optional validated config label or, by default, `VR-` plus the six-character Device ID through the
server-advertised HTTPS send-only contract (204
plus `X-Packetsrcvd`) with app-private credentials. The legacy Android API path uses a pinned ISRG
Root X1 trust anchor for the APRS endpoint. Exact coordinates are persisted only long enough to
suppress a restart duplicate and are cleared when tracking is disabled. No tracking code, location
request or APRS network path is created for T99.

Each accepted position comment carries a compact redacted T56 health snapshot: GPS accuracy,
battery percentage and charging state, battery temperature, Wi-Fi RSSI, mobile network type/RSSI
when available, and free app-volume storage. Unavailable radio fields are marked `NA`; no SSID,
IMEI, phone number, serial, Device ID or coordinate is copied into the health comment.

Verified input sources and mappings are in `docs/T99_DEVICE_PROFILE.md` and
`docs/T56_DEVICE_PROFILE.md`.

`RadioPttKeyManager` applies radio defaults at process startup and maps verified alternatives to the
same service-owned PTT path. T99 capture proves F1 is the labelled PTT and F2 is EXIT, so T99 always
resets its push preference to F1 and rejects F2 before consulting stale settings. T56 instead uses
the captured vendor PTT keyCode 261 and explicitly rejects F1 because F1 is its Menu key.
Media/headset keys remain the public MediaSession alternate path. T56 firmware sends keyCode 261 to
the OEM keyguard while the display is off, so the Activity cannot receive the first press. The same
firmware emits `unipro.hotkey.ptt.down` and `unipro.hotkey.ptt.up` broadcasts while keyguard owns the
raw event. `RadioHardwareKeyReceiver` accepts those actions only on the T56 profile and forwards
DOWN/UP to the same `MumlaService` readiness, release and watchdog path.

T56 firmware likewise emits `unipro.hotkey.p2.long` for the one-person key even when keyguard owns
its raw input. The same profile-gated receiver foregrounds `RadioShellActivity` with an identity
toggle request. The Activity deduplicates that request against its normal raw long-hold handler so
one physical hold produces exactly one full-screen Device ID transition.

### Bounded local history

`RadioKeyDiagnostics` stores a bounded 32 KiB app-private trace for whitelisted radio hardware keys.
Each append keeps only complete newest records and rewrites the tail before the limit, so a record
cannot overflow the file; an individual record larger than the limit is skipped. It records DOWN,
the first repeat and UP with keyCode, scanCode, deviceId, source and device name; it never records
text, configuration, credentials or audio. The service's in-memory chat/info history is likewise
bounded to the newest 256 entries and is cleared on disconnect.

## Standard build versus radio build

The current branch keeps one standard Mumla application and adds radio code under the normal source
set. T99's OEM resolver excludes the data-installed Minimum activity from its usable HOME choices,
so registering Minimum as HOME causes an unacceptable chooser. The radio dashboard is therefore an
explicit boot/provisioning activity, not an Android HOME handler. Launcher3 remains the system HOME
and receives a Minimum recovery shortcut. Provisioning verifies that system HOME opens without
ResolverActivity before returning to the dashboard. Do not fork the protocol/audio core or
duplicate PTT safety logic.
