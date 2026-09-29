import type { DeviceSummary } from './types';

export function filterDevices(devices: DeviceSummary[], query: string): DeviceSummary[] {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return devices;

  return devices.filter((device) => {
    const searchableText = `${device.deviceId} ${device.label}`.toLocaleLowerCase();
    return terms.every((term) => searchableText.includes(term));
  });
}
