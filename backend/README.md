# Minimum static configuration backend

This directory is the public, read-only configuration reference/recovery distribution for the
Minimum radio client. It is designed to be published as GitHub Pages and contains no GitHub token,
password, protected Mumble token, or device secret. Provisioned managed devices fetch their complete
device-specific configuration directly from `https://minimum.vra.or.th/api/device-config/{deviceId}`;
they do not merge these files during normal refresh.

Config schema 3 separates selectable `channels` from keyed `connections`. Each channel references
one connection, so channels may use different hosts, ports, usernames, server passwords, TLS pins
and access tokens. Keyed connections also let a device overlay change one login without replacing
the complete channel list. `deviceId` remains only the `/devices/{deviceId}.json` lookup key.

The Android client should use these endpoints after Pages is enabled:

```text
/manifest.json
/default.json
/models/{modelProfile}.json
/devices/{deviceId}.json
```

The checked-in defaults intentionally keep Mumble auto-connect disabled until a real public Mumble
host is configured. Replace the placeholder host in `default.json` only through a reviewed commit.
Public access tokens may be placed on individual channels, but they are not secrets. Server
passwords and protected access tokens must only exist in a device-private config or future secure
store; they must never be committed to this public directory.

Tracking is also disabled by default in public data. APRS passcodes, private endpoint overrides,
cached positions and Health telemetry must remain device-private. On verified T56 hardware the app
accepts an optional public `tracking.aprs.objectName` label of 1-9 safe ASCII characters. If absent,
it derives the Object name locally as `VR-` plus the six-character Device ID. The backend must not
enable tracking for T99/RYKS/generic profiles. The full wire, receipt and privacy contract is in
[APRS_TRACKING.md](../docs/APRS_TRACKING.md).

Connection-level `autoTrustServerCertificate` defaults to `true`. If normal Android TLS validation rejects a
configured Mumble endpoint, Minimum automatically stores the presented leaf certificate in its
app-private trust store and reconnects. The validated external config is therefore the trust root
for selecting the server host. `serverCertificateSha256` remains an optional stricter pin;
when present, a mismatch fails closed instead of using automatic trust.

Every effective configuration change must advance `configVersion`. Minimum stages validated
downloads as pending, waits for RX/TX and connection transitions to become idle, and promotes the
candidate only after it connects and joins its configured room. A failed candidate is discarded and
the device continues with its Last Known Good active config.

## GitHub Pages

The workflow in `.github/workflows/deploy-pages.yml` publishes this directory. The repository Pages
source is already configured as `GitHub Actions`; after the workflow reaches `main`, the expected
base URL is `https://awatchar.github.io/minimum/`.
