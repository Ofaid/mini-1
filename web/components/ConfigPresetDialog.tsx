'use client';

import { AlertCircle, Copy, Eye, EyeOff, Plus, Save, Trash2, X } from 'lucide-react';
import { FormEvent, useEffect, useState } from 'react';
import type {
  ConfigPresetAccess,
  ConfigPresetChannel,
  ConfigPresetConnection,
  SafeConfigPreset,
  StoredConfigPreset
} from '@/lib/config-library-types';
import type { ConfigPresetSecretUpdate } from '@/lib/config-preset-storage';

type PresetDraft = {
  schemaVersion: 1;
  id: string;
  name: string;
  connection: ConfigPresetConnection;
  channels: ConfigPresetChannel[];
};

export type ConfigPresetDialogProps = {
  open: boolean;
  preset?: SafeConfigPreset | null;
  mode?: 'create' | 'edit' | 'duplicate';
  onClose: () => void;
  onSaved: (preset: StoredConfigPreset, secretPolicy?: ConfigPresetSecretUpdate) => Promise<void> | void;
};

function slugify(value: string) {
  const slug = value.toLocaleLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 56);
  return slug || 'preset';
}

function newChannel(connectionId: string, index = 1): ConfigPresetChannel {
  return { id: `channel-${index}`, label: `Channel ${index}`, connectionId, path: '/', access: { mode: 'none' } };
}

function toDraft(preset: SafeConfigPreset | null | undefined, mode: ConfigPresetDialogProps['mode']): PresetDraft {
  if (!preset) {
    return {
      schemaVersion: 1,
      id: 'new-preset',
      name: '',
      connection: { id: 'server-1', name: 'Primary server', host: '', port: 64738, autoTrustServerCertificate: true },
      channels: [newChannel('server-1')]
    };
  }
  const id = mode === 'duplicate' ? `${preset.id}-copy` : preset.id;
  return {
    schemaVersion: 1,
    id,
    name: mode === 'duplicate' ? `${preset.name} copy` : preset.name,
    connection: {
      id: preset.connection.id,
      ...(preset.connection.name === undefined ? {} : { name: preset.connection.name }),
      host: preset.connection.host,
      port: preset.connection.port,
      autoTrustServerCertificate: preset.connection.autoTrustServerCertificate,
      // Safe detail intentionally does not contain secret values. These are replacement fields.
      ...(preset.connection.sensitive.username ? { username: '' } : {}),
      ...(preset.connection.sensitive.password ? { password: '' } : {}),
      ...(preset.connection.sensitive.fingerprint ? { serverCertificateSha256: '' } : {})
    },
    channels: preset.channels.map((channel) => ({
      id: channel.id,
      label: channel.label,
      ...(channel.alias === undefined ? {} : { alias: channel.alias }),
      connectionId: channel.connectionId,
      path: channel.path,
      ...(channel.presetKey === undefined ? {} : { presetKey: channel.presetKey }),
      access: {
        mode: channel.access.mode,
        ...(channel.access.sensitive.publicTokens ? { tokens: [''] } : {}),
        ...(channel.access.sensitive.protectedTokenRef ? { tokenRef: '' } : {})
      }
    }))
  };
}

function secretValue(value: string | undefined, existing: boolean) {
  if (value) return value;
  return existing ? '••••••••' : '';
}

function TextField({ label, value, onChange, type = 'text', placeholder, help, required = false, disabled = false }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  placeholder?: string;
  help?: string;
  required?: boolean;
  disabled?: boolean;
}) {
  return <label className="field"><span>{label}</span><input value={value} onChange={(event) => onChange(event.target.value)} type={type} placeholder={placeholder} required={required} disabled={disabled} />{help && <small className="field-help">{help}</small>}</label>;
}

function SecretField({ label, value, onChange, placeholder, help }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  help?: string;
}) {
  const [visible, setVisible] = useState(false);
  return <label className="field secret-field"><span>{label}</span><span className="secret-input"><input value={value} onChange={(event) => onChange(event.target.value)} type={visible ? 'text' : 'password'} placeholder={placeholder} autoComplete="new-password" /><button type="button" className="icon-button" onClick={() => setVisible((current) => !current)} aria-label={visible ? `Hide ${label}` : `Show ${label}`} title={visible ? 'Hide value' : 'Show value'}>{visible ? <EyeOff size={15} /> : <Eye size={15} />}</button></span>{help && <small className="field-help">{help}</small>}</label>;
}

function accessWithPatch(access: ConfigPresetAccess, patch: Partial<ConfigPresetAccess>): ConfigPresetAccess {
  return { ...access, ...patch };
}

function existingChannelForDraft(preset: SafeConfigPreset | null, channel: ConfigPresetChannel) {
  return preset?.channels.find((candidate) => candidate.id === channel.id);
}

export function ConfigPresetDialog({ open, preset = null, mode = 'create', onClose, onSaved }: ConfigPresetDialogProps) {
  const [draft, setDraft] = useState<PresetDraft>(() => toDraft(preset, mode));
  const [clearSecrets, setClearSecrets] = useState<ConfigPresetSecretUpdate>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const title = mode === 'edit' ? 'Edit preset' : mode === 'duplicate' ? 'Duplicate preset' : 'Create preset';

  useEffect(() => {
    if (open) {
      setDraft(toDraft(preset, mode));
      setClearSecrets({});
      setError('');
      setBusy(false);
    }
  }, [open, preset, mode]);

  const canSubmit = Boolean(draft.id.trim() && draft.name.trim() && draft.connection.host.trim() && draft.channels.length > 0);

  function updateConnection(patch: Partial<ConfigPresetConnection>) {
    setDraft((current) => ({ ...current, connection: { ...current.connection, ...patch } }));
  }

  function updateChannel(index: number, patch: Partial<ConfigPresetChannel>) {
    setDraft((current) => ({ ...current, channels: current.channels.map((channel, currentIndex) => currentIndex === index ? { ...channel, ...patch } : channel) }));
  }

  function addChannel() {
    setDraft((current) => {
      let index = current.channels.length + 1;
      while (current.channels.some((channel) => channel.id === `channel-${index}`)) index += 1;
      return { ...current, channels: [...current.channels, newChannel(current.connection.id, index)] };
    });
  }

  function removeChannel(index: number) {
    setDraft((current) => current.channels.length <= 1 ? current : { ...current, channels: current.channels.filter((_, currentIndex) => currentIndex !== index) });
  }

  function normalizeSecret(value: string | undefined, existing: boolean) {
    const trimmed = value?.trim() || '';
    // The safe API never returns secret values. A masked placeholder is not a value to persist.
    if (existing && (trimmed === '••••••••' || trimmed === '')) return undefined;
    return trimmed || undefined;
  }

  function buildPreset(): StoredConfigPreset {
    const connection: ConfigPresetConnection = {
      id: draft.connection.id,
      name: draft.connection.name?.trim() || undefined,
      host: draft.connection.host.trim(),
      port: Number(draft.connection.port),
      autoTrustServerCertificate: draft.connection.autoTrustServerCertificate !== false
    };
    const existingConnection = preset?.connection;
    const username = normalizeSecret(draft.connection.username, Boolean(existingConnection?.sensitive.username));
    const password = normalizeSecret(draft.connection.password, Boolean(existingConnection?.sensitive.password));
    const fingerprint = normalizeSecret(draft.connection.serverCertificateSha256, Boolean(existingConnection?.sensitive.fingerprint));
    if (username) connection.username = username;
    if (password) connection.password = password;
    if (fingerprint) connection.serverCertificateSha256 = fingerprint;
    const channels = draft.channels.map((channel, index) => {
      const access = channel.access || { mode: 'none' as const };
      const nextAccess: ConfigPresetAccess = { mode: access.mode };
      if (access.mode === 'public') {
        const tokens = (access.tokens || []).map((token) => token.trim()).filter((token) => token && token !== '••••••••');
        if (tokens.length) nextAccess.tokens = tokens;
      }
      if (access.mode === 'protected' && access.tokenRef?.trim() && access.tokenRef.trim() !== '••••••••') nextAccess.tokenRef = access.tokenRef.trim();
      return {
        id: channel.id.trim() || `channel-${index + 1}`,
        label: channel.label.trim(),
        ...(channel.alias?.trim() ? { alias: channel.alias.trim() } : {}),
        connectionId: connection.id,
        path: channel.path.trim() || '/',
        ...(channel.presetKey?.trim() ? { presetKey: channel.presetKey.trim().toUpperCase() } : {}),
        access: nextAccess
      };
    });
    return { schemaVersion: 1, id: draft.id.trim(), name: draft.name.trim(), connection, channels };
  }

  function secretPolicy(): ConfigPresetSecretUpdate | undefined {
    if (!preset || mode !== 'edit') return undefined;
    const policy: ConfigPresetSecretUpdate = {};
    const masked = (value: string | undefined) => !value || value === '••••••••';
    if (clearSecrets.username) policy.username = 'clear';
    else if (preset.connection.sensitive.username && !masked(draft.connection.username)) policy.username = 'replace';
    if (clearSecrets.password) policy.password = 'clear';
    else if (preset.connection.sensitive.password && !masked(draft.connection.password)) policy.password = 'replace';
    if (clearSecrets.fingerprint) policy.fingerprint = 'clear';
    else if (preset.connection.sensitive.fingerprint && !masked(draft.connection.serverCertificateSha256)) policy.fingerprint = 'replace';
    const channelPolicies: NonNullable<ConfigPresetSecretUpdate['channels']> = {};
    preset.channels.forEach((channel, index) => {
      const next = draft.channels.find((candidate) => candidate.id === channel.id);
      if (!next) return;
      const channelPolicy: NonNullable<ConfigPresetSecretUpdate['channels']>[string] = {};
      const clearChannel = clearSecrets.channels?.[channel.id] || {};
      if (clearChannel.publicTokens === 'clear') channelPolicy.publicTokens = 'clear';
      else if (channel.access.sensitive.publicTokens && next.access.mode === 'public' && !masked(next.access.tokens?.[0])) channelPolicy.publicTokens = 'replace';
      if (clearChannel.protectedTokenRef === 'clear') channelPolicy.protectedTokenRef = 'clear';
      else if (channel.access.sensitive.protectedTokenRef && next.access.mode === 'protected' && !masked(next.access.tokenRef)) channelPolicy.protectedTokenRef = 'replace';
      if (Object.keys(channelPolicy).length > 0) channelPolicies[channel.id] = channelPolicy;
    });
    if (Object.keys(channelPolicies).length > 0) policy.channels = channelPolicies;
    return policy;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      const next = buildPreset();
      if (!next.id || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(next.id)) throw new Error('Preset ID must use lowercase letters, numbers and hyphens.');
      if (!next.name) throw new Error('Preset name is required.');
      if (!next.connection.host) throw new Error('Server address is required.');
      if (!Number.isInteger(next.connection.port) || next.connection.port < 1 || next.connection.port > 65535) throw new Error('Port must be an integer from 1 to 65535.');
      if (next.channels.some((channel) => !channel.id || !channel.label || !channel.path.startsWith('/'))) throw new Error('Each channel needs an ID, label and path starting with /.');
      const duplicateIds = next.channels.map((channel) => channel.id).filter((id, index, all) => all.indexOf(id) !== index);
      if (duplicateIds.length) throw new Error(`Channel ID ${duplicateIds[0]} is duplicated.`);
      await onSaved(next, secretPolicy());
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save preset');
    } finally { setBusy(false); }
  }

  if (!open) return null;
  if (mode === 'duplicate') return <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <section className="dialog-card config-preset-dialog" role="dialog" aria-modal="true" aria-labelledby="preset-dialog-title">
      <div className="dialog-heading"><div><span className="eyebrow">CONFIGURATION LIBRARY</span><h2 id="preset-dialog-title">Duplicate preset</h2><p>Create a copy with a new ID and name. The selected preset’s connection, channels and masked secrets are copied unchanged.</p></div><button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label="Close dialog" title="Close"><X size={17} /></button></div>
      <form onSubmit={submit} className="dialog-body"><div className="form-grid compact-grid dialog-grid"><TextField label="New preset ID" value={draft.id} onChange={(id) => setDraft((current) => ({ ...current, id: slugify(id) }))} placeholder="operations-copy" required help="Lowercase letters, numbers and hyphens." /><TextField label="New preset name" value={draft.name} onChange={(name) => setDraft((current) => ({ ...current, name }))} placeholder="Operations copy" required /></div><div className="read-only-callout"><Copy size={15} /><span>Only the new ID and name are editable. No other copied values are silently discarded.</span></div>{error && <div className="form-error"><AlertCircle size={15} />{error}</div>}<div className="dialog-footer"><span className="muted">Sensitive values remain masked.</span><div className="dialog-actions"><button type="button" className="quiet-button" onClick={onClose} disabled={busy}>Cancel</button><button type="submit" className="primary-button" disabled={busy || !canSubmit}>{busy ? 'Duplicating...' : 'Duplicate preset'}<Copy size={15} /></button></div></div></form>
    </section>
  </div>;
  return <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <section className="dialog-card config-preset-dialog" role="dialog" aria-modal="true" aria-labelledby="preset-dialog-title">
      <div className="dialog-heading"><div><span className="eyebrow">CONFIGURATION LIBRARY</span><h2 id="preset-dialog-title">{title}</h2><p>Store one reusable server and its channels. Device identity and policy are never included.</p></div><button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label="Close dialog" title="Close"><X size={17} /></button></div>
      <form onSubmit={submit} className="dialog-body">
        <div className="form-grid compact-grid dialog-grid"><TextField label="Preset ID" value={draft.id} onChange={(id) => setDraft((current) => ({ ...current, id: slugify(id) }))} placeholder="operations" required disabled={mode === 'edit'} help={mode === 'edit' ? 'Preset ID cannot be changed while editing.' : 'Lowercase letters, numbers and hyphens.'} /><TextField label="Preset name" value={draft.name} onChange={(name) => setDraft((current) => ({ ...current, name }))} placeholder="Operations server" required /></div>
        <section className="dialog-section"><div className="section-heading"><div><h3>Server connection</h3><p>Secrets are masked and are only released during an explicit import.</p></div></div><div className="form-grid compact-grid dialog-grid"><TextField label="Connection ID" value={draft.connection.id} onChange={(id) => { const nextId = slugify(id); setDraft((current) => ({ ...current, connection: { ...current.connection, id: nextId }, channels: current.channels.map((channel) => ({ ...channel, connectionId: nextId })) })); }} placeholder="server-1" required /><TextField label="Server name" value={draft.connection.name || ''} onChange={(name) => updateConnection({ name })} placeholder="Primary server" /><TextField label="Address" value={draft.connection.host} onChange={(host) => updateConnection({ host })} placeholder="voice.example.org" required /><label className="field"><span>Port</span><input value={draft.connection.port || ''} onChange={(event) => updateConnection({ port: Number(event.target.value) })} type="number" min={1} max={65535} required /></label><SecretField label="Username" value={secretValue(draft.connection.username, Boolean(preset?.connection.sensitive.username))} onChange={(username) => updateConnection({ username })} placeholder={preset?.connection.sensitive.username ? 'Stored value masked' : 'Optional'} help={preset?.connection.sensitive.username ? 'Enter a replacement only when needed.' : undefined} /><SecretField label="Password" value={secretValue(draft.connection.password, Boolean(preset?.connection.sensitive.password))} onChange={(password) => updateConnection({ password })} placeholder={preset?.connection.sensitive.password ? 'Stored value masked' : 'Optional'} help={preset?.connection.sensitive.password ? 'Enter a replacement only when needed.' : undefined} /><SecretField label="Certificate SHA-256" value={secretValue(draft.connection.serverCertificateSha256, Boolean(preset?.connection.sensitive.fingerprint))} onChange={(serverCertificateSha256) => updateConnection({ serverCertificateSha256 })} placeholder={preset?.connection.sensitive.fingerprint ? 'Stored value masked' : 'Optional'} help="64 hexadecimal characters, with or without colons." /></div>{preset && <div className="secret-clear-grid">{([['username', 'Clear stored username', preset.connection.sensitive.username], ['password', 'Clear stored password', preset.connection.sensitive.password], ['fingerprint', 'Clear stored fingerprint', preset.connection.sensitive.fingerprint]] as const).filter(([, , present]) => present).map(([key, label]) => <label className="sensitive-option" key={key}><input type="checkbox" checked={clearSecrets[key] === 'clear'} onChange={(event) => setClearSecrets((current) => ({ ...current, [key]: event.target.checked ? 'clear' : undefined }))} /><span>{label}<small>Explicitly remove this value when saving.</small></span></label>)}</div>}</section>
        <section className="dialog-section"><div className="section-heading"><div><h3>Channels</h3><p>Select the reusable paths that will be offered to a device.</p></div><button type="button" className="quiet-button" onClick={addChannel} disabled={draft.channels.length >= 16}><Plus size={15} /> Add channel</button></div><div className="dialog-repeat-list">{draft.channels.map((channel, index) => { const existingChannel = existingChannelForDraft(preset, channel); return <div className="dialog-repeat-card" key={`${channel.id}-${index}`}><div className="repeat-card-heading"><div><span className="eyebrow">CHANNEL {index + 1}</span><strong>{channel.label || channel.id}</strong></div><button type="button" className="icon-button danger-icon" onClick={() => removeChannel(index)} disabled={draft.channels.length <= 1} aria-label={`Remove channel ${channel.id}`} title="Remove channel"><Trash2 size={15} /></button></div><div className="form-grid compact-grid dialog-grid"><TextField label="Channel ID" value={channel.id} onChange={(id) => updateChannel(index, { id: slugify(id) })} required disabled={mode === 'edit' && Boolean(existingChannel)} help={existingChannel ? 'Existing channel IDs stay fixed so masked secrets remain attached to the correct channel.' : undefined} /><TextField label="Label" value={channel.label} onChange={(label) => updateChannel(index, { label })} required /><TextField label="Alias" value={channel.alias || ''} onChange={(alias) => updateChannel(index, { alias })} placeholder="Optional" /><TextField label="Path" value={channel.path} onChange={(path) => updateChannel(index, { path: path.startsWith('/') ? path : `/${path}` })} placeholder="/PUBLIC/MAIN" required /><TextField label="Preset key" value={channel.presetKey || ''} onChange={(presetKey) => updateChannel(index, { presetKey: presetKey.toUpperCase() })} placeholder="P1 (optional)" /><label className="field"><span>Access mode</span><select value={channel.access.mode} onChange={(event) => { const mode = event.target.value as ConfigPresetAccess['mode']; updateChannel(index, { access: accessWithPatch({ mode }, {}) }); }}><option value="none">No token</option><option value="public">Public token</option><option value="protected">Protected token reference</option></select></label>{channel.access.mode === 'public' && <SecretField label="Public token" value={secretValue(channel.access.tokens?.[0], Boolean(existingChannel?.access.sensitive.publicTokens))} onChange={(token) => updateChannel(index, { access: { mode: 'public', tokens: token ? [token] : [] } })} placeholder="Stored value masked" help="Values are never shown in list or detail responses." />}{channel.access.mode === 'protected' && <TextField label="Protected token reference" value={secretValue(channel.access.tokenRef, Boolean(existingChannel?.access.sensitive.protectedTokenRef))} onChange={(tokenRef) => updateChannel(index, { access: { mode: 'protected', tokenRef } })} placeholder="Alias resolved by the device" />}{preset && <div className="secret-clear-grid channel-secret-clear">{existingChannel?.access.sensitive.publicTokens && <label className="sensitive-option"><input type="checkbox" checked={clearSecrets.channels?.[channel.id]?.publicTokens === 'clear'} onChange={(event) => setClearSecrets((current) => ({ ...current, channels: { ...current.channels, [channel.id]: { ...current.channels?.[channel.id], publicTokens: event.target.checked ? 'clear' : undefined } } }))} /><span>Clear stored public tokens<small>Only this channel is affected.</small></span></label>}{existingChannel?.access.sensitive.protectedTokenRef && <label className="sensitive-option"><input type="checkbox" checked={clearSecrets.channels?.[channel.id]?.protectedTokenRef === 'clear'} onChange={(event) => setClearSecrets((current) => ({ ...current, channels: { ...current.channels, [channel.id]: { ...current.channels?.[channel.id], protectedTokenRef: event.target.checked ? 'clear' : undefined } } }))} /><span>Clear stored protected reference<small>Only this channel is affected.</small></span></label>}</div>}</div></div>; })}</div></section>
        {error && <div className="form-error"><AlertCircle size={15} />{error}</div>}
        <div className="dialog-footer"><span className="muted">Sensitive fields remain masked in the library.</span><div className="dialog-actions"><button type="button" className="quiet-button" onClick={onClose} disabled={busy}>Cancel</button><button type="submit" className="primary-button" disabled={busy || !canSubmit}>{busy ? 'Saving...' : 'Save preset'}<Save size={15} /></button></div></div>
      </form>
    </section>
  </div>;
}
