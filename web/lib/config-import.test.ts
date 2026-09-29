import { describe, expect, it } from 'vitest';
import type { MinimumConfig } from './types';
import { buildImportBundle, applyConfigImport, previewConfigImport } from './config-import';

function targetConfig(): MinimumConfig {
  return {
    schemaVersion: 3,
    configVersion: 44,
    deviceId: 'AB12C3',
    modelProfile: 't56',
    service: { name: 'target-service' },
    hardware: { profile: 'pinned-hardware', pttKeyCode: 261 },
    ptt: { maximumTxSeconds: 90, releaseOnNetworkLoss: true },
    tracking: { enabled: false, aprs: { enabled: false } },
    connections: {
      target: { name: 'Target', host: 'target.example.com', port: 64738, username: 'target-user', autoTrustServerCertificate: true },
      pinned: { name: 'Pinned', host: 'voice.example.com', port: 64738, username: 'target-user', autoTrustServerCertificate: false, serverCertificateSha256: 'BB'.repeat(32) }
    },
    channels: [
      { id: 'target-main', label: 'Target', connectionId: 'target', path: '/MAIN', presetKey: 'P1', access: { mode: 'none' } },
      { id: 'pinned-main', label: 'Pinned', connectionId: 'pinned', path: '/MAIN', access: { mode: 'none' } }
    ],
    radio: { defaultChannel: 'target-main', autoConnect: false },
    ui: { profile: 'small-radio' }
  };
}

function sourceConfig(): MinimumConfig {
  return {
    schemaVersion: 3,
    configVersion: 6,
    deviceId: 'CD34E5',
    modelProfile: 'ryks',
    service: { name: 'source-device-service' },
    hardware: { profile: 'source-hardware', pttKeyCode: 285 },
    ptt: { maximumTxSeconds: 120, releaseOnNetworkLoss: true },
    tracking: { enabled: true, aprs: { enabled: true, sourceCallsign: 'N0CALL' } },
    connections: {
      source: { host: 'Target.Example.COM.', port: 64738, username: 'source-user', autoTrustServerCertificate: true }
    },
    channels: [
      { id: 'source-main', label: 'Source Main', connectionId: 'source', path: '/MAIN', presetKey: 'P1', access: { mode: 'public', tokens: ['zeta', 'alpha'] } },
      { id: 'source-field', label: 'Field', connectionId: 'source', path: '/FIELD', access: { mode: 'protected', tokenRef: 'protected-ref' } }
    ],
    radio: { defaultChannel: 'source-field' },
    ui: { profile: 'source-ui' },
    update: { checkOnBoot: true }
  };
}

describe('pure config import algorithms', () => {
  it('builds a source-only bundle and leaves source untouched', () => {
    const source = sourceConfig();
    const before = JSON.stringify(source);
    const bundle = buildImportBundle(source, { connectionId: 'source', includeDefaultChannel: true });
    expect(bundle).toEqual(expect.objectContaining({ schemaVersion: 1, defaultChannelId: 'source-field' }));
    expect(bundle).not.toHaveProperty('deviceId');
    expect(bundle).not.toHaveProperty('modelProfile');
    expect(bundle).not.toHaveProperty('hardware');
    expect(bundle).not.toHaveProperty('tracking');
    expect(JSON.stringify(source)).toBe(before);
  });

  it('reuses exact semantic connections and rewrites channel references', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'target.example.com', port: 64738, username: 'target-user', autoTrustServerCertificate: true };
    const bundle = buildImportBundle(source, { connectionId: 'source' });
    const target = targetConfig();
    const preview = previewConfigImport(target, bundle, { connectionDuplicate: 'reuse', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect(preview.canApply).toBe(true);
    expect(preview.connectionIdMap.source).toBe('target');
    const applied = applyConfigImport(target, bundle, { connectionDuplicate: 'reuse', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect(Object.keys(applied.connections || {})).toEqual(['target', 'pinned']);
    expect((applied.channels as Array<{ connectionId: string }>).some((channel) => channel.connectionId === 'target')).toBe(true);
  });

  it('blocks a possible duplicate endpoint until explicit decision', () => {
    const bundle = buildImportBundle(sourceConfig(), { connectionId: 'source' });
    const preview = previewConfigImport(targetConfig(), bundle);
    expect(preview.canApply).toBe(false);
    expect(preview.decisions.some((decision) => decision.kind === 'duplicate-connection')).toBe(true);
  });

  it('never duplicates a pinned fingerprint when source omits the pin', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'voice.example.com', port: 64738, username: 'target-user', autoTrustServerCertificate: true };
    const bundle = buildImportBundle(source, { connectionId: 'source' });
    const preview = previewConfigImport(targetConfig(), bundle);
    expect(preview.canApply).toBe(false);
    expect(preview.decisions.some((decision) => decision.kind === 'pinned-fingerprint')).toBe(true);
  });

  it('generates base-2 IDs for same-ID conflicts and resolves duplicate channels explicitly', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'new.example.com', port: 64738, username: 'user', autoTrustServerCertificate: true };
    const sourceChannels = source.channels as Array<Record<string, unknown>>;
    sourceChannels[0].id = 'target-main';
    const bundle = buildImportBundle(source, { connectionId: 'source' });
    const target = targetConfig();
    const preview = previewConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect(preview.channelIdMap['target-main']).toBe('target-main-2');
    const applied = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect((applied.channels as Array<{ id: string }>).some((channel) => channel.id === 'target-main-2')).toBe(true);
  });

  it('supports duplicate channel skip/add/replace and safe P1 conflict handling', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'new.example.com', port: 64738, username: 'user', autoTrustServerCertificate: true };
    const bundle = buildImportBundle(source, { connectionId: 'source' });
    const target = targetConfig();
    const unresolved = previewConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'skip' });
    expect(unresolved.canApply).toBe(false);
    expect(unresolved.decisions.some((decision) => decision.kind === 'preset-key-conflict')).toBe(true);
    const skipped = previewConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'skip', presetKeyConflict: 'unused' });
    expect(skipped.canApply).toBe(true);
    const applied = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'skip', presetKeyConflict: 'unused' });
    expect((applied.channels as Array<Record<string, unknown>>).filter((channel) => channel.presetKey === 'P1')).toHaveLength(1);
    const added = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect((added.channels as Array<Record<string, unknown>>).filter((channel) => channel.presetKey === 'P1')).toHaveLength(1);
    const replaced = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'replace' });
    expect((replaced.channels as Array<Record<string, unknown>>).filter((channel) => channel.presetKey === 'P1')).toHaveLength(1);
  });

  it('preserves target device fields/version/default unless explicitly mapped', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'new.example.com', port: 64738, username: 'user', autoTrustServerCertificate: true };
    const bundle = buildImportBundle(source, { connectionId: 'source', includeDefaultChannel: true });
    const target = targetConfig();
    const originalVersion = target.configVersion;
    const originalHardware = JSON.stringify(target.hardware);
    const unchanged = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    expect(unchanged.configVersion).toBe(originalVersion);
    expect(JSON.stringify(unchanged.hardware)).toBe(originalHardware);
    expect((unchanged.radio as Record<string, unknown>).defaultChannel).toBe('target-main');
    const mapped = applyConfigImport(target, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused', importDefaultChannel: true });
    expect((mapped.radio as Record<string, unknown>).defaultChannel).toBe('source-field');
  });

  it('enforces capacity and never leaks secrets through preview', () => {
    const source = sourceConfig();
    (source.connections as Record<string, unknown>).source = { host: 'new.example.com', port: 64738, username: 'user', password: 'secret', autoTrustServerCertificate: true };
    const bundle = buildImportBundle(source, { connectionId: 'source' });
    const preview = previewConfigImport(targetConfig(), bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' });
    const encoded = JSON.stringify(preview);
    expect(encoded).not.toContain('secret');
    expect(encoded).not.toContain('source-user');
    const tooMany = targetConfig();
    tooMany.channels = Array.from({ length: 16 }, (_, index) => ({ id: `c${index}`, label: `C${index}`, connectionId: 'target', path: `/C${index}`, access: { mode: 'none' } }));
    expect(() => applyConfigImport(tooMany, bundle, { connectionDuplicate: 'add', channelDuplicate: 'add', presetKeyConflict: 'unused' })).toThrow(/16-channel/);
  });
});
