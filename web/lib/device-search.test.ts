import { describe, expect, it } from 'vitest';
import type { DeviceSummary } from './types';
import { filterDevices } from './device-search';

function device(deviceId: string, label: string): DeviceSummary {
  return {
    deviceId,
    label,
    model: 't99',
    configVersion: 1,
    createdAt: '2026-08-10T00:00:00.000Z',
    updatedAt: '2026-08-10T00:00:00.000Z'
  };
}

const devices = [
  device('GYZ3DE', 'North Command'),
  device('T56-OPS-02', 'Rescue Team Two'),
  device('T99-HQ-01', 'Headquarters')
];

describe('device registry search', () => {
  it('matches device IDs without case sensitivity', () => {
    expect(filterDevices(devices, 't56-ops').map((item) => item.deviceId)).toEqual(['T56-OPS-02']);
  });

  it('matches display labels without case sensitivity', () => {
    expect(filterDevices(devices, 'north command').map((item) => item.deviceId)).toEqual(['GYZ3DE']);
  });

  it('supports multiple terms across device ID and display label', () => {
    expect(filterDevices(devices, 't99 headquarters').map((item) => item.deviceId)).toEqual(['T99-HQ-01']);
  });

  it('returns the original list for an empty query and no matches otherwise', () => {
    expect(filterDevices(devices, '   ')).toBe(devices);
    expect(filterDevices(devices, 'missing')).toEqual([]);
  });
});
