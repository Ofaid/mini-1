import { describe, expect, it } from 'vitest';
import {
  applyModelProfile,
  configsEqual,
  effectiveConfigsEqual,
  emptyConfig,
  prepareConfigForSave,
  repairConfig,
  validateConfig
} from './config';
import type { MinimumConfig } from './types';
import { validModelProfile } from './model-profiles';
import { calculateAprsPasscode } from './aprs';

describe('Minimum config validation', () => {
  it('accepts the schema 3 device baseline', () => {
    const config = emptyConfig('AB12C3');
    expect(validateConfig(config, 'AB12C3')).toEqual({ valid: true, errors: [] });
  });

  it('rejects a device mismatch and invalid version', () => {
    const config = emptyConfig('AB12C3');
    config.deviceId = 'ZX98Y7';
    config.configVersion = 0;
    const result = validateConfig(config, 'AB12C3');
    expect(result.valid).toBe(false);
    expect(result.errors.join(' ')).toContain('configVersion');
    expect(result.errors.join(' ')).toContain('deviceId');
  });

  it('compares configuration independent of object key order', () => {
    expect(configsEqual({ b: 2, a: 1 }, { a: 1, b: 2 })).toBe(true);
  });

  it('rejects dangling channel references before persistence', () => {
    const config = emptyConfig('AB12C3') as MinimumConfig & {
      radio: { defaultChannel: string };
      channels: Array<{ connectionId: string }>;
    };
    config.radio.defaultChannel = 'missing-channel';
    config.channels[0].connectionId = 'missing-connection';
    const result = validateConfig(config, 'AB12C3');
    expect(result.valid).toBe(false);
    expect(result.errors.join(' ')).toContain('connectionId');
    expect(result.errors.join(' ')).toContain('defaultChannel');
  });

  it('builds model-specific hardware baselines from supported profile choices', () => {
    const t56 = emptyConfig('AB12C3', 't56');
    const t99 = emptyConfig('ZX98Y7', 't99');
    const ryks = emptyConfig('RYK258', 'ryks');
    expect((t56.hardware as { profile: string }).profile).toBe('t56-unipro-zx-l809');
    expect((t99.hardware as { profile: string }).profile).toBe('t99-qm011');
    expect((ryks.hardware as { profile: string }).profile).toBe('ryks-elink-ym-258');
    expect(t56.modelProfile).toBe('t56');
    expect(t99.modelProfile).toBe('t99');
    expect(ryks.modelProfile).toBe('ryks');
    expect((t56.hardware as { locationTrackingSupported: boolean }).locationTrackingSupported).toBe(true);
    expect((t99.hardware as { locationTrackingSupported: boolean }).locationTrackingSupported).toBe(false);
    expect((ryks.hardware as { pttScanCode: number }).pttScanCode).toBe(216);
    expect((ryks.hardware as { pttScanCodes: number[] }).pttScanCodes).toEqual([216, 249]);
    expect((ryks.hardware as { p1ScanCode: number }).p1ScanCode).toBe(66);
    expect((ryks.hardware as { p2ScanCode: number }).p2ScanCode).toBe(65);
    expect((ryks.hardware as { menuKeyCode: number }).menuKeyCode).toBe(132);
    expect((ryks.hardware as { menuScanCode: number }).menuScanCode).toBe(60);
    expect((ryks.hardware as { redKeyCode: number }).redKeyCode).toBe(26);
    expect((ryks.hardware as { redScanCode: number }).redScanCode).toBe(116);
    expect(validModelProfile('ryks')).toBe(true);
    expect(validModelProfile('t56')).toBe(true);
    expect(validModelProfile('T-56 typo')).toBe(false);
  });

  it('creates every canonical section without enabling tracking or APRS', () => {
    for (const model of ['ryks', 't56', 't99', 'generic-radio'] as const) {
      const config = emptyConfig('AB12C3', model);
      expect(config).toMatchObject({
        schemaVersion: 3,
        service: expect.any(Object),
        radio: expect.any(Object),
        connections: expect.any(Object),
        channels: expect.any(Array),
        ui: expect.any(Object),
        ptt: expect.any(Object),
        tracking: { enabled: false, aprs: { enabled: false } },
        hardware: expect.any(Object),
        update: expect.any(Object)
      });
      expect(validateConfig(config, 'AB12C3')).toEqual({ valid: true, errors: [] });
    }
  });

  it('repairs a partial config while preserving supplied channel and connection values', () => {
    const repaired = repairConfig({
      deviceId: 'AB12C3',
      modelProfile: 't56',
      configVersion: 4,
      connections: {
        'private-main': {
          name: 'Private',
          host: 'radio.example.invalid',
          port: 64739,
          username: 'operator',
          password: 'placeholder-not-a-credential',
          autoTrustServerCertificate: false
        }
      },
      channels: [{
        id: 'private',
        label: 'Private room',
        alias: 'OPS',
        connectionId: 'private-main',
        path: '/OPS',
        presetKey: 'P2',
        access: { mode: 'protected', tokenRef: 'private-token-ref' }
      }],
      radio: { defaultChannel: 'private' },
      tracking: { aprs: { objectName: 'OPS56' } }
    });

    expect(repaired.configVersion).toBe(7);
    expect((repaired.connections as Record<string, Record<string, unknown>>)['private-main'].password)
      .toBe('placeholder-not-a-credential');
    expect(Object.keys(repaired.connections as Record<string, unknown>))
      .toEqual(['private-main']);
    expect((repaired.connections as Record<string, unknown>)['public-main']).toBeUndefined();
    expect((repaired.channels as Array<Record<string, unknown>>)[0]).toMatchObject({
      alias: 'OPS', presetKey: 'P2', connectionId: 'private-main'
    });
    expect((repaired.tracking as Record<string, unknown>).aprs).toMatchObject({
      enabled: false, objectName: 'OPS56'
    });
    expect(validateConfig(repaired, 'AB12C3')).toEqual({ valid: true, errors: [] });

    const legacyChannels = repairConfig({
      deviceId: 'AB12C3',
      channels: [{
        id: 'main', label: 'Main room', connectionId: 'public-main', path: '/PUBLIC/MAIN',
        access: { mode: 'none' }
      }]
    });
    expect((legacyChannels.channels as Array<Record<string, unknown>>)[0]).toMatchObject({
      alias: 'MAIN', presetKey: 'P1'
    });

    const legacy = emptyConfig('AB12C3', 't56');
    delete legacy.modelProfile;
    const repairedLegacy = repairConfig(legacy);
    expect(repairedLegacy.modelProfile).toBe('t56');
    expect((repairedLegacy.hardware as Record<string, unknown>).locationTrackingSupported).toBe(true);
  });

  it('changes model-owned values only and disables unsupported tracking', () => {
    const original = emptyConfig('AB12C3', 't56');
    const connections = original.connections as Record<string, Record<string, unknown>>;
    const channels = original.channels as Array<Record<string, unknown>>;
    const tracking = original.tracking as Record<string, unknown>;
    const aprs = tracking.aprs as Record<string, unknown>;
    connections['public-main'].password = 'placeholder-not-a-credential';
    channels[0].access = { mode: 'protected', tokenRef: 'placeholder-token-ref' };
    aprs.objectName = 'OPS56';
    aprs.enabled = true;
    tracking.enabled = true;

    const changed = applyModelProfile(original, 't99');
    expect(changed).not.toBe(original);
    expect((changed.service as Record<string, unknown>).name).toBe('Minimum T99');
    expect((changed.hardware as Record<string, unknown>).profile).toBe('t99-qm011');
    expect((changed.hardware as Record<string, unknown>).locationTrackingSupported).toBe(false);
    expect((changed.tracking as Record<string, unknown>).enabled).toBe(false);
    expect(((changed.tracking as Record<string, unknown>).aprs as Record<string, unknown>).enabled).toBe(false);
    expect(((changed.tracking as Record<string, unknown>).aprs as Record<string, unknown>).objectName).toBe('OPS56');
    expect((changed.connections as Record<string, Record<string, unknown>>)['public-main'].password)
      .toBe('placeholder-not-a-credential');
    expect((changed.channels as Array<Record<string, unknown>>)[0].access)
      .toEqual({ mode: 'protected', tokenRef: 'placeholder-token-ref' });
    expect(tracking.enabled).toBe(true);
    expect(aprs.enabled).toBe(true);
  });

  it('advances version only for an effective save change', () => {
    const previous = emptyConfig('AB12C3', 't56');
    previous.configVersion = 7;
    const unchanged = prepareConfigForSave({ ...previous, configVersion: 1 }, previous);
    expect(unchanged.configVersion).toBe(7);
    expect(effectiveConfigsEqual(unchanged, previous)).toBe(true);

    const draft = repairConfig(previous, 'AB12C3', 't56');
    (draft.ui as Record<string, unknown>).showChat = true;
    const changed = prepareConfigForSave({ ...draft, configVersion: 1 }, previous);
    expect(changed.configVersion).toBe(8);
    expect((changed.ui as Record<string, unknown>).showChat).toBe(true);
    const imported = prepareConfigForSave({ ...draft, configVersion: 1004 }, previous);
    expect(imported.configVersion).toBe(1004);
  });

  it('persists a selected default channel and advances its config version', () => {
    const previous = repairConfig({
      ...emptyConfig('AB12C3', 't56'),
      channels: [
        { id: 'dispatch', label: 'Dispatch', alias: 'DISPATCH', connectionId: 'public-main', path: '/DISPATCH', access: { mode: 'none' } },
        { id: 'field', label: 'Field', alias: 'FIELD', connectionId: 'public-main', path: '/FIELD', access: { mode: 'none' } }
      ],
      radio: { defaultChannel: 'dispatch', autoConnect: true, autoReconnect: true }
    }, 'AB12C3', 't56');
    previous.configVersion = 20;

    const changed = prepareConfigForSave({
      ...previous,
      radio: { ...(previous.radio as Record<string, unknown>), defaultChannel: 'field' }
    }, previous, 'AB12C3');

    expect((changed.radio as Record<string, unknown>).defaultChannel).toBe('field');
    expect(changed.configVersion).toBe(21);
    expect(validateConfig(changed, 'AB12C3')).toEqual({ valid: true, errors: [] });
  });

  it('rejects unsupported tracking and missing public/protected access requirements', () => {
    const unsupported = emptyConfig('AB12C3', 't99');
    (unsupported.tracking as Record<string, unknown>).enabled = true;
    const trackingResult = validateConfig(unsupported, 'AB12C3');
    expect(trackingResult.valid).toBe(false);
    expect(trackingResult.errors.join(' ')).toContain('locationTrackingSupported');

    const publicAccess = emptyConfig('AB12C3');
    (publicAccess.channels as Array<Record<string, unknown>>)[0].access = { mode: 'public' };
    const publicResult = validateConfig(publicAccess, 'AB12C3');
    expect(publicResult.valid).toBe(false);
    expect(publicResult.errors.join(' ')).toContain('token');

    const protectedAccess = emptyConfig('AB12C3');
    (protectedAccess.channels as Array<Record<string, unknown>>)[0].access = { mode: 'protected' };
    const protectedResult = validateConfig(protectedAccess, 'AB12C3');
    expect(protectedResult.valid).toBe(false);
    expect(protectedResult.errors.join(' ')).toContain('tokenRef');
  });

  it('matches the Android APRS passcode range', () => {
    const config = emptyConfig('AB12C3', 't56');
    const tracking = config.tracking as Record<string, unknown>;
    tracking.enabled = true;
    tracking.aprs = {
      enabled: true,
      sourceCallsign: 'E25FGL',
      passcode: '32767',
      host: 'ametx.com',
      port: 8888
    };
    expect(validateConfig(config, 'AB12C3')).toEqual({ valid: true, errors: [] });
    (tracking.aprs as Record<string, unknown>).passcode = '32768';
    expect(validateConfig(config, 'AB12C3').valid).toBe(false);
  });

  it('does not require APRS credentials while APRS is disabled or unspecified', () => {
    const config = emptyConfig('AB12C3', 't56');
    const tracking = config.tracking as Record<string, unknown>;
    tracking.aprs = {};
    expect(validateConfig(config, 'AB12C3')).toEqual({ valid: true, errors: [] });
  });

  it('calculates the standard APRS-IS passcode from the base callsign', () => {
    expect(calculateAprsPasscode('N0CALL')).toBe('13023');
    expect(calculateAprsPasscode('n0call-9')).toBe('13023');
    expect(calculateAprsPasscode('AB')).toBe('');
  });
});
