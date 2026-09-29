const SOURCE_CALLSIGN_PATTERN = /^(?!.*-0$)[A-Z0-9]{3,6}(?:-[0-9]{1,2})?$/;

export function normalizeAprsCallsign(value: string): string {
  return value.trim().toUpperCase();
}

/** APRS-IS login passcode derived from the base callsign; any SSID is ignored. */
export function calculateAprsPasscode(value: string): string {
  const callsign = normalizeAprsCallsign(value);
  if (!SOURCE_CALLSIGN_PATTERN.test(callsign)) return '';
  const base = callsign.split('-', 1)[0];
  let hash = 0x73e2;
  for (let index = 0; index < base.length; index += 2) {
    hash ^= base.charCodeAt(index) << 8;
    if (index + 1 < base.length) hash ^= base.charCodeAt(index + 1);
  }
  return String(hash & 0x7fff);
}
