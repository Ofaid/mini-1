import { beforeEach, describe, expect, it } from 'vitest';
import {
  CONFIG_PRESET_MAX_BYTES,
  CONFIG_PRESET_MAX_COUNT,
  createConfigPreset,
  deleteConfigPreset,
  getConfigPreset,
  listConfigPresets,
  safeConfigPresetRecord,
  updateConfigPreset
} from './config-preset-storage';
import { kvList, resetMemoryStore } from './storage';
import type { StoredConfigPreset } from './config-library-types';

function preset(id = 'ops-preset'): StoredConfigPreset {
  return {
    schemaVersion: 1,
    id,
    name: 'Operations',
    connection: {
      id: 'server', host: 'voice.example.com', port: 64738,
      username: 'operator', password: 'top-secret', serverCertificateSha256: 'AA'.repeat(32), autoTrustServerCertificate: false
    },
    channels: [{
      id: 'main', label: 'Main', connectionId: 'server', path: '/MAIN', presetKey: 'P1',
      access: { mode: 'public', tokens: ['alpha'] }
    }]
  };
}

describe('config preset storage', () => {
  beforeEach(() => {
    resetMemoryStore();
  });

  it('stores CRUD records and returns safe presence-only representations', async () => {
    const created = await createConfigPreset(preset(), '2026-08-12T00:00:00.000Z');
    expect(created.createdAt).toBe('2026-08-12T00:00:00.000Z');
    const safe = safeConfigPresetRecord(created);
    const encoded = JSON.stringify(safe);
    expect(encoded).not.toContain('top-secret');
    expect(encoded).not.toContain('operator');
    expect(encoded).not.toContain('alpha');
    expect(safe.connection.sensitive).toEqual({ username: true, password: true, fingerprint: true });
    expect(safe.channels[0].access.sensitive.publicTokens).toBe(true);
    const updated = await updateConfigPreset('ops-preset', { ...preset(), name: 'Updated' }, created.updatedAt, '2026-08-12T00:01:00.000Z');
    expect(updated.preset.name).toBe('Updated');
    expect((await listConfigPresets('updated')).map((entry) => entry.preset.id)).toEqual(['ops-preset']);
    await deleteConfigPreset('ops-preset', updated.updatedAt);
    expect(await getConfigPreset('ops-preset')).toBeNull();
  });

  it('enforces create conflicts and optimistic update/delete conflicts', async () => {
    const created = await createConfigPreset(preset());
    await expect(createConfigPreset(preset())).rejects.toMatchObject({ code: 'conflict' });
    await expect(updateConfigPreset('ops-preset', preset(), 'stale')).rejects.toMatchObject({ code: 'conflict' });
    await expect(deleteConfigPreset('ops-preset', 'stale')).rejects.toMatchObject({ code: 'conflict' });
    await expect(getConfigPreset('missing')).resolves.toBeNull();
  });

  it('preserves masked secrets by default and supports explicit replace/clear', async () => {
    const created = await createConfigPreset(preset(), '2026-08-12T00:00:00.000Z');
    const original = preset();
    const { username: _username, password: _password, serverCertificateSha256: _fingerprint, ...connectionWithoutSecrets } = original.connection;
    const safeShape = { ...original, name: 'Renamed', connection: connectionWithoutSecrets, channels: [{ ...original.channels[0], access: { mode: 'public' as const } }] };
    const preserved = await updateConfigPreset('ops-preset', safeShape, created.updatedAt, '2026-08-12T00:01:00.000Z');
    expect(preserved.preset.connection.username).toBe('operator');
    expect(preserved.preset.connection.password).toBe('top-secret');
    expect(preserved.preset.channels[0].access.tokens).toEqual(['alpha']);
    const cleared = await updateConfigPreset('ops-preset', safeShape, preserved.updatedAt, '2026-08-12T00:02:00.000Z', { username: 'clear', password: 'clear', fingerprint: 'clear', publicTokens: 'clear' });
    expect(cleared.preset.connection.username).toBeUndefined();
    expect(cleared.preset.connection.password).toBeUndefined();
    expect(cleared.preset.channels[0].access.tokens).toBeUndefined();
  });

  it('searches connection names and channel labels, aliases and paths', async () => {
    await createConfigPreset({ ...preset(), connection: { ...preset().connection, name: 'Field Operations' }, channels: [{ ...preset().channels[0], label: 'Dispatch', alias: 'OPS', path: '/NORTH' }] });
    expect((await listConfigPresets('field')).length).toBe(1);
    expect((await listConfigPresets('dispatch')).length).toBe(1);
    expect((await listConfigPresets('ops')).length).toBe(1);
    expect((await listConfigPresets('/north')).length).toBe(1);
  });

  it('applies public and protected secret modes independently per channel', async () => {
    const multi = preset('multi-secret');
    multi.channels = [
      { id: 'public-a', label: 'Public A', connectionId: 'server', path: '/A', access: { mode: 'public', tokens: ['alpha'] } },
      { id: 'public-b', label: 'Public B', connectionId: 'server', path: '/B', access: { mode: 'public', tokens: ['bravo'] } },
      { id: 'protected-c', label: 'Protected C', connectionId: 'server', path: '/C', access: { mode: 'protected', tokenRef: 'charlie-ref' } }
    ];
    const created = await createConfigPreset(multi, '2026-08-12T00:00:00.000Z');
    const redacted = { ...multi, channels: multi.channels.map((channel) => ({ ...channel, access: { mode: channel.access.mode } })) };
    const preserved = await updateConfigPreset('multi-secret', redacted, created.updatedAt, '2026-08-12T00:01:00.000Z');
    expect(preserved.preset.channels.map((channel) => channel.access.tokens?.[0] || channel.access.tokenRef)).toEqual(['alpha', 'bravo', 'charlie-ref']);
    const replacedInput = { ...redacted, channels: redacted.channels.map((channel) => channel.id === 'public-b' ? { ...channel, access: { mode: 'public' as const, tokens: ['bravo-new'] } } : channel.id === 'protected-c' ? { ...channel, access: { mode: 'protected' as const, tokenRef: 'charlie-new' } } : channel) };
    const replaced = await updateConfigPreset('multi-secret', replacedInput, preserved.updatedAt, '2026-08-12T00:02:00.000Z', { channels: { 'public-b': { publicTokens: 'replace' }, 'protected-c': { protectedTokenRef: 'replace' } } });
    expect(replaced.preset.channels.map((channel) => channel.access.tokens?.[0] || channel.access.tokenRef)).toEqual(['alpha', 'bravo-new', 'charlie-new']);
    const reorderedRenamed = { ...redacted, channels: [redacted.channels[1], redacted.channels[2], { ...redacted.channels[0], id: 'renamed-a' }] };
    const reordered = await updateConfigPreset('multi-secret', reorderedRenamed, replaced.updatedAt, '2026-08-12T00:03:00.000Z');
    expect(reordered.preset.channels.map((channel) => channel.id)).toEqual(['public-b', 'protected-c', 'renamed-a']);
    expect(reordered.preset.channels[0].access.tokens).toEqual(['bravo-new']);
    expect(reordered.preset.channels[1].access.tokenRef).toBe('charlie-new');
    expect(reordered.preset.channels[2].access.tokens).toBeUndefined();
    const removed = await updateConfigPreset('multi-secret', { ...redacted, channels: [redacted.channels[1]] }, reordered.updatedAt, '2026-08-12T00:04:00.000Z');
    expect(removed.preset.channels.map((channel) => channel.id)).toEqual(['public-b']);
    expect(removed.preset.channels[0].access.tokens).toEqual(['bravo-new']);
    const cleared = await updateConfigPreset('multi-secret', { ...redacted, channels: [redacted.channels[1], redacted.channels[2]] }, removed.updatedAt, '2026-08-12T00:05:00.000Z', { channels: { 'public-b': { publicTokens: 'clear' }, 'protected-c': { protectedTokenRef: 'clear' } } });
    expect(cleared.preset.channels[0].access.tokens).toBeUndefined();
    expect(cleared.preset.channels[1].access.tokenRef).toBeUndefined();
  });

  it('rejects payloads over 64 KiB and malformed canonical presets', async () => {
    const oversized = preset();
    oversized.name = 'x'.repeat(CONFIG_PRESET_MAX_BYTES);
    await expect(createConfigPreset(oversized)).rejects.toMatchObject({ code: 'invalid' });
    await expect(createConfigPreset({ schemaVersion: 1, id: 'bad id' })).rejects.toMatchObject({ code: 'invalid' });
  });

  it('enforces the 100-preset capacity', async () => {
    for (let index = 0; index < CONFIG_PRESET_MAX_COUNT; index += 1) {
      await createConfigPreset(preset(`preset-${index}`));
    }
    await expect(createConfigPreset(preset('one-too-many'))).rejects.toMatchObject({ code: 'capacity' });
    expect((await kvList('config-preset:')).length).toBe(CONFIG_PRESET_MAX_COUNT);
  });
});
