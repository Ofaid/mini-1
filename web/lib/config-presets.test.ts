import { describe, expect, it } from 'vitest';
import type { MinimumConfig } from './types';
import {
  createPresetFromSelection,
  normalizeFingerprint,
  normalizeHost,
  normalizePublicTokens,
  redactConfigPreset,
  safeConfigPresetRepresentation,
  validateConfigPreset
} from './config-presets';

function sampleConfig(): MinimumConfig {
  return {
    schemaVersion: 3,
    configVersion: 91,
    deviceId: 'AB12C3',
    modelProfile: 't99',
    service: { name: 'device service' },
    hardware: { profile: 'device-hardware', pttKeyCode: 9 },
    ptt: { maximumTxSeconds: 120, releaseOnNetworkLoss: true },
    tracking: { enabled: true, aprs: { sourceCallsign: 'N0CALL' } },
    connections: {
      'server-main': {
        name: 'Server', host: ' Voice.Example.COM. ', port: 64738,
        username: 'operator', password: 'secret-password',
        serverCertificateSha256: 'aa:'.repeat(31) + 'aa', autoTrustServerCertificate: false
      }
    },
    channels: [
      { id: 'ops', label: 'Operations', connectionId: 'server-main', path: '/OPS', presetKey: 'P1', access: { mode: 'public', tokens: [' zeta ', 'alpha', 'alpha'] } },
      { id: 'private', label: 'Private', connectionId: 'server-main', path: '/PRIVATE', access: { mode: 'protected', tokenRef: 'device-secret-ref' } }
    ],
    radio: { defaultChannel: 'ops' }
  };
}

describe('config preset domain', () => {
  it('normalizes hosts, fingerprints, and public tokens deterministically', () => {
    expect(normalizeHost('  Voice.Example.COM... ')).toBe('voice.example.com');
    expect(normalizeFingerprint('aa:'.repeat(31) + 'aa')).toBe('AA'.repeat(32));
    expect(normalizePublicTokens([' zeta ', 'alpha', 'alpha'])).toEqual(['zeta', 'alpha']);
  });

  it('creates a safe one-server preset by default and records every omission', () => {
    const preset = createPresetFromSelection(sampleConfig(), {
      id: 'ops-preset', name: 'Operations preset', connectionId: 'server-main'
    });
    expect(preset.connection.host).toBe('voice.example.com');
    expect(preset.connection).not.toHaveProperty('username');
    expect(preset.connection).not.toHaveProperty('password');
    expect(preset.connection).not.toHaveProperty('serverCertificateSha256');
    expect(preset.channels[0].access).not.toHaveProperty('tokens');
    expect(preset.channels[1].access).not.toHaveProperty('tokenRef');
    expect(preset.omissions).toEqual({ username: true, password: true, fingerprint: true, publicTokens: true, protectedTokenRef: true });
  });

  it('supports explicit inclusion without ever moving public tokens into tokenRef', () => {
    const preset = createPresetFromSelection(sampleConfig(), {
      id: 'ops-preset', name: 'Operations preset', connectionId: 'server-main',
      username: true, password: true, fingerprint: true, publicTokens: true, protectedTokenRef: true
    });
    expect(preset.connection.username).toBe('operator');
    expect(preset.connection.password).toBe('secret-password');
    expect(preset.connection.serverCertificateSha256).toBe('AA'.repeat(32));
    expect(preset.channels[0].access.tokens).toEqual(['zeta', 'alpha']);
    expect(preset.channels[0].access).not.toHaveProperty('tokenRef');
    expect(preset.channels[1].access.tokenRef).toBe('device-secret-ref');
    expect(preset.omissions).toBeUndefined();
  });

  it('returns presence-only safe representations', () => {
    const preset = createPresetFromSelection(sampleConfig(), {
      id: 'ops-preset', name: 'Operations preset', connectionId: 'server-main',
      username: true, password: true, fingerprint: true, publicTokens: true, protectedTokenRef: true
    });
    const safe = safeConfigPresetRepresentation(preset);
    const encoded = JSON.stringify(safe);
    expect(encoded).not.toContain('secret-password');
    expect(encoded).not.toContain('operator');
    expect(encoded).not.toContain('device-secret-ref');
    expect(encoded).not.toContain('alpha');
    expect(safe.connection.sensitive).toEqual({ username: true, password: true, fingerprint: true });
    expect(safe.channels[0].access.sensitive.publicTokens).toBe(true);
    expect(safe.channels[1].access.sensitive.protectedTokenRef).toBe(true);
  });

  it('rejects extra properties, invalid preset IDs, and invalid references', () => {
    const valid = createPresetFromSelection(sampleConfig(), { id: 'ops-preset', name: 'Preset', connectionId: 'server-main' });
    expect(validateConfigPreset({ ...valid, extra: true }).valid).toBe(false);
    expect(validateConfigPreset({ ...valid, id: 'Not A Slug' }).valid).toBe(false);
    expect(validateConfigPreset({ ...valid, channels: [{ ...valid.channels[0], connectionId: 'other' }] }).valid).toBe(false);
  });

  it('does not mutate the source config while copying selected channels', () => {
    const source = sampleConfig();
    const before = JSON.stringify(source);
    const preset = createPresetFromSelection(source, { id: 'ops-preset', name: 'Preset', connectionId: 'server-main', channelIds: ['ops'] });
    preset.channels[0].label = 'changed';
    expect(JSON.stringify(source)).toBe(before);
  });

  it('redacts a previously included preset back to safe defaults', () => {
    const included = createPresetFromSelection(sampleConfig(), {
      id: 'ops-preset', name: 'Preset', connectionId: 'server-main',
      username: true, password: true, fingerprint: true, publicTokens: true, protectedTokenRef: true
    });
    const safe = redactConfigPreset(included);
    expect(safe.omissions).toEqual({ username: true, password: true, fingerprint: true, publicTokens: true, protectedTokenRef: true });
  });
});
