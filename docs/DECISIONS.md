# Engineering decisions

## D-001: Keep Humla/Mumla voice core

The radio client reuses Mumble protocol, TLS, Opus, audio and the existing foreground service. A
small UI does not justify rewriting a proven voice path or creating two PTT safety implementations.

## D-002: Device ID is an app identity, not a hardware identifier

Minimum uses a generated six-character ID persisted in app preferences. It avoids leaking IMEI,
serial or MAC information and is suitable for public device configuration lookup. Clearing app data
generates a new ID.

## D-003: Public config contains policy, not protected secrets

GitHub Pages is public and reviewable. Protected access tokens remain device-local. Future server
authentication must use a secure reference or provisioning flow rather than committing a token.

## D-004: PTT safety is service-owned

The service releases TX on key-up, disconnect, service destruction and watchdog timeout. UI and
hardware bridges call the service but do not duplicate its timing or safety state.

## D-005: Do not guess OEM key mappings

T99 exposes several Linux input devices and Android may suppress or transform events. A mapping is
added only after a live foreground/background/screen-off trace. Unknown keys remain unclaimed.

## D-006: User-0 Zello uninstall is the reversible practical operation

The T99 system partition is not modified. `pm uninstall --user 0` removes Zello for the working user
and the script verifies the exact package/device before acting. Factory reset may restore it.

## D-007: Radio dashboard is explicit; Launcher3 is the recovery HOME

T99/T56 devices need a visible route to Minimum even when the OEM Launcher3 workspace only contains
Settings. `MinimumHomeActivity` provides one large action per swipe page for Minimum and Settings.
T99's OEM resolver does not offer the data-installed Minimum activity as a usable default HOME and
therefore displays a broken chooser. Minimum does not register as HOME. Boot and provisioning open
the dashboard explicitly, while a legacy shortcut adds Minimum to Launcher3 for recovery. The
script fails if system HOME still exposes ResolverActivity.

## D-008: Embedded default always exists

Remote configuration is optional. The app must start with a validated embedded configuration even if
GitHub Pages, DNS, or the network is unavailable.

## D-009: Radio PTT defaults are automatic, but screen-off capability is evidence-based

Supported radio profiles initialize PTT mode and known alternatives at application startup. Live T99
capture overrides the earlier generic assumption: F1 is PTT and F2 is EXIT. T99 therefore forces F1
and rejects F2 regardless of stale preferences. T56 uses its captured vendor PTT keyCode 261 and
rejects F1 because that physical control is Menu. Media/headset keys use MediaSession as alternates.

## D-010: Validated external config authorizes automatic server-certificate trust

Minimum first uses Android's normal TLS trust. If it rejects a configured Mumble server and
`autoTrustServerCertificate` is true (the default), the presented leaf certificate is placed in the
existing app-private BKS trust store and the connection is retried without an operator dialog. The
external config is the intended trust boundary for choosing the server. An optional configured
SHA-256 fingerprint is stricter: when present, it must match and automatic trust cannot bypass it.
No system CA store or global TLS verifier is disabled. This policy means config-source compromise
can redirect devices; signed config remains future hardening.

## D-011: Downloaded config is not Last Known Good until the radio proves it

A validated remote response is staged as pending and trialled only when connection state, RX and TX
are idle. It becomes active only after Minimum connects and joins the configured room. Connection,
permission, room or TLS failure discards the candidate and reconnects with the prior active config.
Promotion retains the old active file as previous for explicit rollback. This separates transport
and schema validity from operational validity and prevents a bad backend edit from bricking a radio.

## D-012: Managed radios retry transport failures, not server rejections

Transport disconnects use capped 15/30/60-second exponential backoff and continue until Mumble
synchronizes. Every managed-radio connection path, including network return, channel/server change
and service-process recovery, shares a persisted 15-second minimum attempt interval. Server reject,
kick, ban and other non-transport failures stop retrying so invalid authentication or policy cannot
create an attempt storm. This is based on Mumble's verified default IP autoban policy: 10 attempts
inside 120 seconds, a 300-second ban, successful connections counted, and a ban when the count
exceeds the configured limit. A certificate pin or trust-policy mismatch remains fail-closed.

## D-013: PTT delivery warning is local evidence, not a server receipt

Minimum warns when PTT is pressed while unsynchronized or when no encoded audio packet is handed to
the synchronized Mumble connection within the confirmation window. Mumble voice has no handset-
visible per-packet acknowledgement, so end-to-end acceptance still requires a second client or
server-side observer. Documentation and UI must not claim that local packet handoff proves a remote
listener heard the audio.

## D-014: Dedicated radios use a renewable process watchdog lease

`START_REDELIVER_INTENT` remains the first service-recovery mechanism, but OEM firmware may impose a
restart backoff that is unacceptable for PTT availability. T99/T56 services therefore renew a
30-second AlarmManager lease every 10 seconds. Lease expiry explicitly reopens RadioShell and
re-arms recovery until the service heartbeat resumes. The lease is profile-gated and modern Android
uses an inexact permission-free alarm fallback rather than requiring special exact-alarm access.

## D-015: Recovery PTT is never queued, and deliberate keys require holds

An offline PTT press alerts, foregrounds RadioShell where Android permits and requests an immediate
connection, but that press is never replayed after connection. The operator presses again after
Ready; this avoids stuck TX when the original key-up is delivered to another window or lost during
recovery. Because Android can retarget a still-held key into the new Activity, Minimum arms a
service-backed release-required lock before launching RadioShell and keeps the Activity locked until
key-up. Managed-radio TX is service-gated on synchronization, PTT mode and verified entry into the
configured room. On T99, MENU/EXIT/red require a five-second hold to leave RadioShell, while
Up/Down require a one-second hold and then select and join directly. Raw F1 is not described as a
global Android key: unrelated foreground apps require a separately proven OEM, privileged or
keylayout integration.

## D-016: Managed radios suppress normal PTT confirmation sound

The short Mumla sound played when local TX begins is disabled for T99/T56 at both managed defaults
and service runtime, including when a stale preference requests it. Hardware feel and the full-
screen TX state provide sufficient normal feedback. The separate failure tone remains mandatory so
an operator is warned when a press cannot be delivered locally.

## D-017: Channel selection is a persistent connection target

Schema 3 separates stable selectable channels from keyed connection profiles. A channel references
its server connection and owns its access tokens, so selecting another channel may require a full
disconnect/authenticate/join cycle rather than only an in-session room move. Minimum persists only
the selected channel ID, never its password or tokens, and restores it after Activity/process/
connection recovery. A removed ID falls back to `radio.defaultChannel`. Keyed connection objects
preserve deep-merge device overrides without replacing the entire channel array. Inline server
passwords and protected tokens are permitted only in app-private provisioned config, never in the
public backend.

## D-018: APRS tracking is a T56 Object with piggybacked health

Tracking location is represented as an APRS Object, never as the source callsign's station
position. By default the Object name is `VR-` plus the six-character Minimum Device ID (nine
characters total), so operators can use `VR-*` wildcard searches without exposing hardware
identifiers. An optional validated `tracking.aprs.objectName` may replace that public label when an
operator needs a stable functional name; omission always preserves the Device ID fallback. Stationary,
walking and vehicle states use distinct APRS symbols as part of the packet contract. Battery,
network and storage health is appended to the existing position beacon rather than sent as separate
packets, which keeps the beacon cadence bounded and makes health and position share one timestamp.
The immutable T56-only gate is deliberate: T99 has not produced a usable location fix and must not
silently begin public tracking if remote configuration changes.

## D-019: Managed configuration is served by the private portal

The Vercel portal at `minimum.vra.or.th` is the authoritative control plane for registered device
records and complete Schema-3 configuration. Android retains active/previous/pending copies
app-privately. GitHub Pages remains a public, non-secret reference and recovery artifact.

This decision's manual bearer-token enrollment was superseded by D-021.

## D-021: Device Profile creation is the only control-plane enrollment step

Managed radios fetch by their stable six-character Device ID. Operators create the Device Profile
in the authenticated admin portal but do not copy or rotate a second bearer token on the handset.
Unknown IDs are retained briefly in the pending queue and receive `404`; registered IDs receive the
validated config. Android refreshes at every process start as well as on the six-hour/network-return
schedule, so reboot and OTA do not require physical reprovisioning.

## D-020: Default channel is a fallback, not a forced selection

`radio.defaultChannel` is edited explicitly in the portal's **Channels & default** UI. Android first
restores its app-private Last Selected Channel across process and connection recovery. It uses the
configured default only when no saved selection exists or that channel was removed. This avoids a
routine portal save unexpectedly moving an operator's active radio channel.
