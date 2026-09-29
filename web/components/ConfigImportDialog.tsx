'use client';

import { AlertCircle, Check, Copy, Eye, Library, RefreshCw, ShieldCheck, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { MinimumConfig } from '@/lib/types';
import type {
  ConfigImportOptions,
  ConfigImportPreview,
  SafeConfigPreset,
  ConfigPresetSecretInclusion
} from '@/lib/config-library-types';

type DeviceOption = { deviceId: string; label: string; model: string };
type SourceMode = 'preset' | 'device';

export type ConfigImportDialogProps = {
  open: boolean;
  targetDeviceId: string;
  targetDraft: MinimumConfig;
  initialMode?: SourceMode;
  onClose: () => void;
  onApplied: (draft: MinimumConfig) => void;
};

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...(init?.headers || {}) },
    cache: 'no-store'
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(typeof data?.error === 'string' ? data.error : 'Request failed');
  return data as T;
}

function records(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function channelsFromConfig(config: MinimumConfig | null) {
  const source = records(config);
  const channels = Array.isArray(source.channels) ? source.channels : [];
  return channels.filter((channel): channel is Record<string, unknown> => channel !== null && typeof channel === 'object' && !Array.isArray(channel) && typeof channel.id === 'string');
}

function connectionsFromConfig(config: MinimumConfig | null) {
  const source = records(config);
  const connections = records(source.connections);
  return Object.entries(connections).filter(([, connection]) => connection !== null && typeof connection === 'object' && !Array.isArray(connection)).map(([id, value]) => ({ id, value: value as Record<string, unknown> }));
}

function defaultChannelFromConfig(config: MinimumConfig | null): string | undefined {
  const source = records(config);
  const radio = records(source.radio);
  return typeof radio.defaultChannel === 'string' && radio.defaultChannel.trim()
    ? radio.defaultChannel.trim()
    : undefined;
}

function defaultInclusion(): ConfigPresetSecretInclusion {
  return { username: false, password: false, fingerprint: false, publicTokens: false, protectedTokenRef: false };
}

function selectionKey(value: string, suffix = '') { return `${value}:${suffix}`; }

export function ConfigImportDialog({ open, targetDeviceId, targetDraft, initialMode = 'preset', onClose, onApplied }: ConfigImportDialogProps) {
  const [mode, setMode] = useState<SourceMode>(initialMode);
  const [presets, setPresets] = useState<SafeConfigPreset[]>([]);
  const [devices, setDevices] = useState<DeviceOption[]>([]);
  const [selectedPresetId, setSelectedPresetId] = useState('');
  const [sourceDeviceId, setSourceDeviceId] = useState('');
  const [sourceConfig, setSourceConfig] = useState<MinimumConfig | null>(null);
  const [connectionId, setConnectionId] = useState('');
  const [channelIds, setChannelIds] = useState<string[]>([]);
  const [includeDefaultChannel, setIncludeDefaultChannel] = useState(false);
  const [inclusion, setInclusion] = useState<ConfigPresetSecretInclusion>(() => defaultInclusion());
  const [decisions, setDecisions] = useState<ConfigImportOptions>({});
  const [preview, setPreview] = useState<ConfigImportPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const generationRef = useRef(0);
  const requestControllerRef = useRef<AbortController | null>(null);
  const targetDraftRef = useRef(targetDraft);

  function invalidateImport() {
    generationRef.current += 1;
    requestControllerRef.current?.abort();
    requestControllerRef.current = null;
    setPreview(null);
    setError('');
    setDecisions({});
    setInclusion(defaultInclusion());
    setIncludeDefaultChannel(false);
    setBusy(false);
  }

  const selectedPreset = useMemo(() => presets.find((preset) => preset.id === selectedPresetId) || null, [presets, selectedPresetId]);
  const sourceConnections = useMemo(() => mode === 'device' ? connectionsFromConfig(sourceConfig) : selectedPreset ? [{ id: selectedPreset.connection.id, value: selectedPreset.connection }] : [], [mode, sourceConfig, selectedPreset]);
  const sourceChannels = useMemo(() => mode === 'device' ? channelsFromConfig(sourceConfig) : selectedPreset?.channels || [], [mode, sourceConfig, selectedPreset]);
  const sourceDefaultChannelId = mode === 'device' ? defaultChannelFromConfig(sourceConfig) : undefined;
  const canIncludeDefaultChannel = mode === 'device' && Boolean(sourceDefaultChannelId);
  const selectedConnection = sourceConnections.find((connection) => connection.id === connectionId);
  const selectedChannelSet = useMemo(() => new Set(channelIds), [channelIds]);
  const sourceReady = Boolean((mode === 'preset' ? selectedPresetId : sourceDeviceId) && connectionId && channelIds.length > 0);
  const decisionCount = preview?.decisions.length || 0;

  useEffect(() => {
    if (!open) return;
    setMode(initialMode);
    invalidateImport(); setSourceConfig(null);
    setSelectedPresetId(''); setSourceDeviceId(''); setConnectionId(''); setChannelIds([]); setIncludeDefaultChannel(false);
    const generation = generationRef.current;
    const controller = new AbortController();
    requestControllerRef.current = controller;
    setLoading(true);
    Promise.all([
      request<{ presets: SafeConfigPreset[] }>('/api/config-presets', { signal: controller.signal }),
      request<{ devices: DeviceOption[] }>('/api/devices', { signal: controller.signal })
    ]).then(([presetData, deviceData]) => {
      if (generation !== generationRef.current || controller.signal.aborted) return;
      setPresets(presetData.presets || []);
      setDevices(deviceData.devices || []);
    }).catch((err) => {
      if (generation === generationRef.current && !(err instanceof Error && err.name === 'AbortError')) setError(err instanceof Error ? err.message : 'Could not load configuration sources');
    }).finally(() => {
      if (generation === generationRef.current) setLoading(false);
      if (requestControllerRef.current === controller) requestControllerRef.current = null;
    });
    return () => {
      controller.abort();
      generationRef.current += 1;
      requestControllerRef.current?.abort();
      requestControllerRef.current = null;
    };
  }, [open, initialMode]);

  useEffect(() => {
    if (!open || mode !== 'device' || !sourceDeviceId) { setSourceConfig(null); return; }
    const generation = generationRef.current;
    let cancelled = false;
    const controller = new AbortController();
    requestControllerRef.current = controller;
    setLoading(true); setError(''); setPreview(null); setConnectionId(''); setChannelIds([]);
    request<{ device: { config: MinimumConfig } }>(`/api/devices/${encodeURIComponent(sourceDeviceId)}`, { signal: controller.signal }).then((data) => { if (!cancelled && generation === generationRef.current) setSourceConfig(data.device.config); }).catch((err) => { if (!cancelled && generation === generationRef.current && err instanceof Error && err.name !== 'AbortError') setError(err.message || 'Could not load source device'); }).finally(() => { if (!cancelled && generation === generationRef.current) { setLoading(false); if (requestControllerRef.current === controller) requestControllerRef.current = null; } });
    return () => { cancelled = true; controller.abort(); };
  }, [open, mode, sourceDeviceId]);

  useEffect(() => {
    const first = sourceConnections[0]?.id || '';
    if (first && !sourceConnections.some((connection) => connection.id === connectionId)) setConnectionId(first);
  }, [sourceConnections, connectionId]);

  useEffect(() => {
    const available = sourceChannels.filter((channel) => {
      const channelConnection = typeof channel.connectionId === 'string' ? channel.connectionId : connectionId;
      return channelConnection === connectionId && typeof channel.id === 'string';
    }).map((channel) => String(channel.id));
    setChannelIds((current) => {
      const next = current.filter((id) => available.includes(id));
      if (next.length === current.length && next.every((id, index) => id === current[index])) return current;
      return next;
    });
  }, [sourceChannels, connectionId]);

  useEffect(() => {
    if (!open) {
      targetDraftRef.current = targetDraft;
      return;
    }
    if (targetDraftRef.current !== targetDraft) {
      targetDraftRef.current = targetDraft;
      invalidateImport();
    }
  }, [targetDraft, open]);

  function toggleChannel(id: string) {
    setChannelIds((current) => current.includes(id) ? current.filter((value) => value !== id) : [...current, id]);
    setPreview(null);
  }

  function updateDecision(kind: string, choice: string) {
    setDecisions((current) => {
      const next = { ...current };
      if (kind === 'duplicate-connection' || kind === 'pinned-fingerprint') next.connectionDuplicate = choice === 'reuse' ? 'reuse' : choice === 'add' ? 'add' : undefined;
      if (kind === 'duplicate-channel') next.channelDuplicate = choice as ConfigImportOptions['channelDuplicate'];
      if (kind === 'preset-key-conflict') next.presetKeyConflict = choice as ConfigImportOptions['presetKeyConflict'];
      return next;
    });
    if (kind === 'pinned-fingerprint' && choice === 'include') setInclusion((current) => ({ ...current, fingerprint: true }));
    setPreview(null);
  }

  function body(decisionValues = decisions) {
    // Presets intentionally do not store radio.defaultChannel. Never send a
    // stale checkbox value when the source is a library preset.
    const includeDefault = canIncludeDefaultChannel && includeDefaultChannel;
    const selection = { connectionId, channelIds, includeDefaultChannel: includeDefault };
    return {
      targetDraft,
      ...(mode === 'preset' ? { presetId: selectedPresetId } : { sourceDeviceId }),
      selection,
      inclusion,
      decisions: { ...decisionValues, importDefaultChannel: includeDefault }
    };
  }

  async function runPreview() {
    if (!sourceReady) { setError('Choose a source connection and at least one channel.'); return; }
    const generation = generationRef.current;
    const controller = new AbortController();
    requestControllerRef.current?.abort(); requestControllerRef.current = controller;
    setBusy(true); setError('');
    try {
      const data = await request<{ preview: ConfigImportPreview }>(`/api/devices/${encodeURIComponent(targetDeviceId)}/config-import/preview`, { method: 'POST', body: JSON.stringify(body()), signal: controller.signal });
      if (generation === generationRef.current) setPreview(data.preview);
    } catch (err) { if (generation === generationRef.current && !(err instanceof Error && err.name === 'AbortError')) setError(err instanceof Error ? err.message : 'Could not preview import'); }
    finally { if (generation === generationRef.current) setBusy(false); if (requestControllerRef.current === controller) requestControllerRef.current = null; }
  }

  async function apply() {
    if (!preview?.canApply) { setError('Resolve every preview conflict before applying.'); return; }
    const generation = generationRef.current;
    const controller = new AbortController();
    requestControllerRef.current?.abort(); requestControllerRef.current = controller;
    setBusy(true); setError('');
    try {
      const data = await request<{ draft: MinimumConfig }>(`/api/devices/${encodeURIComponent(targetDeviceId)}/config-import/apply`, { method: 'POST', body: JSON.stringify(body()), signal: controller.signal });
      if (generation === generationRef.current) { onApplied(data.draft); onClose(); }
    } catch (err) { if (generation === generationRef.current && !(err instanceof Error && err.name === 'AbortError')) setError(err instanceof Error ? err.message : 'Could not apply import to draft'); }
    finally { if (generation === generationRef.current) setBusy(false); if (requestControllerRef.current === controller) requestControllerRef.current = null; }
  }

  if (!open) return null;
  const sourceLabel = mode === 'preset' ? selectedPreset?.name || 'Choose a preset' : devices.find((device) => device.deviceId === sourceDeviceId)?.label || sourceDeviceId || 'Choose a device';
  return <div className="dialog-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose(); }}>
    <section className="dialog-card config-import-dialog" role="dialog" aria-modal="true" aria-labelledby="import-dialog-title">
      <div className="dialog-heading"><div><span className="eyebrow">DRAFT-ONLY IMPORT</span><h2 id="import-dialog-title">Add reusable configuration</h2><p>Preview and merge into the unsaved draft for <code>{targetDeviceId}</code>. Nothing is persisted until Save configuration.</p></div><button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label="Close dialog" title="Close"><X size={17} /></button></div>
      <div className="import-mode-tabs" role="tablist"><button type="button" role="tab" aria-selected={mode === 'preset'} className={mode === 'preset' ? 'editor-tab active' : 'editor-tab'} onClick={() => { invalidateImport(); setMode('preset'); setSourceConfig(null); setConnectionId(''); setChannelIds([]); }}><Library size={15} /> From library</button><button type="button" role="tab" aria-selected={mode === 'device'} className={mode === 'device' ? 'editor-tab active' : 'editor-tab'} onClick={() => { invalidateImport(); setMode('device'); setSourceConfig(null); setConnectionId(''); setChannelIds([]); }}><Copy size={15} /> Copy from device</button></div>
      <div className="dialog-body import-body">
        {loading && <div className="dialog-state"><RefreshCw size={17} className="spin" />Loading sources...</div>}
        {!loading && mode === 'preset' && <label className="field"><span>Library preset</span><select value={selectedPresetId} onChange={(event) => { invalidateImport(); setSelectedPresetId(event.target.value); setConnectionId(''); setChannelIds([]); }}>{<option value="">Choose a preset</option>}{presets.map((preset) => <option value={preset.id} key={preset.id}>{preset.name} ({preset.id})</option>)}</select></label>}
        {!loading && mode === 'device' && <label className="field"><span>Source device</span><select value={sourceDeviceId} onChange={(event) => { invalidateImport(); setSourceDeviceId(event.target.value); setSourceConfig(null); setConnectionId(''); setChannelIds([]); }}><option value="">Choose a device</option>{devices.filter((device) => device.deviceId !== targetDeviceId).map((device) => <option value={device.deviceId} key={device.deviceId}>{device.label || device.deviceId} ({device.deviceId})</option>)}</select></label>}
        {!loading && (selectedPreset || sourceConfig) && <><div className="import-source-summary"><span className="device-mark"><ShieldCheck size={15} /></span><span><strong>{sourceLabel}</strong><small>{selectedConnection ? `${String(selectedConnection.value.host || '')}:${String(selectedConnection.value.port || '')}` : 'Select a source connection'}</small></span></div><label className="field"><span>Source connection</span><select value={connectionId} onChange={(event) => { setConnectionId(event.target.value); setChannelIds([]); setPreview(null); }}>{sourceConnections.map((connection) => <option value={connection.id} key={connection.id}>{String(connection.value.name || connection.id)} ({connection.id})</option>)}</select></label><div className="import-channel-picker"><div className="import-picker-heading"><span><strong>Channels to add</strong><small>{channelIds.length} selected · device-specific fields excluded</small></span><button type="button" className="quiet-button" onClick={() => { setChannelIds(sourceChannels.filter((channel) => (channel.connectionId === connectionId || !channel.connectionId) && typeof channel.id === 'string').map((channel) => String(channel.id))); setPreview(null); }}>Select all</button></div>{sourceChannels.filter((channel) => (channel.connectionId === connectionId || !channel.connectionId) && typeof channel.id === 'string').map((channel) => { const id = String(channel.id); return <label className="import-channel-option" key={id}><input type="checkbox" checked={selectedChannelSet.has(id)} onChange={() => toggleChannel(id)} /><span><strong>{String(channel.alias || channel.label || id)}</strong><small>{String(channel.path || '/')} · {id}</small></span><Check size={15} aria-hidden="true" /></label>; })}</div>{canIncludeDefaultChannel && <label className="toggle-field"><span><strong>Include source default channel</strong><small>{sourceDefaultChannelId} from the source device; only available when copying from a device.</small></span><input type="checkbox" checked={includeDefaultChannel} onChange={(event) => { setIncludeDefaultChannel(event.target.checked); setPreview(null); }} /><i aria-hidden="true" /></label>}<section className="sensitive-options"><div><strong>Include sensitive values</strong><small>Values remain masked. Every field is opt-in for this import.</small></div>{(['username', 'password', 'fingerprint', 'publicTokens', 'protectedTokenRef'] as const).map((key) => { const label = key === 'publicTokens' ? 'Public tokens' : key === 'protectedTokenRef' ? 'Protected token references' : key === 'fingerprint' ? 'Certificate fingerprint' : key[0].toUpperCase() + key.slice(1); const present = mode === 'preset' ? Boolean(selectedPreset && (key === 'username' ? selectedPreset.connection.sensitive.username : key === 'password' ? selectedPreset.connection.sensitive.password : key === 'fingerprint' ? selectedPreset.connection.sensitive.fingerprint : selectedPreset.channels.some((channel) => channel.access.sensitive[key === 'publicTokens' ? 'publicTokens' : 'protectedTokenRef'])) ) : true; return <label className="sensitive-option" key={key}><input type="checkbox" checked={inclusion[key] === true} disabled={!present} onChange={(event) => { setInclusion((current) => ({ ...current, [key]: event.target.checked })); setPreview(null); }} /><span>{label}<small>{present ? 'Available in source' : 'Not present'}</small></span></label>; })}</section></>}
        {error && <div className="form-error"><AlertCircle size={15} />{error}</div>}
        {preview && <section className={`import-preview ${preview.canApply ? 'import-preview-ok' : 'import-preview-blocked'}`} aria-live="polite"><div className="import-preview-heading"><div><span className="eyebrow">PREVIEW</span><h3>{preview.canApply ? 'Ready to merge into draft' : 'Resolution required'}</h3></div><span className="status-badge">{preview.resultingCounts.connections} connections · {preview.resultingCounts.channels} channels</span></div>{preview.errors.length > 0 && <ul className="import-issues import-errors">{preview.errors.map((item) => <li key={item}>{item}</li>)}</ul>}{preview.warnings.length > 0 && <ul className="import-issues import-warnings">{preview.warnings.map((item) => <li key={item}>{item}</li>)}</ul>}{preview.decisions.length > 0 && <div className="import-decisions"><strong>Resolve conflicts before applying</strong>{preview.decisions.map((decision, index) => <label className="import-decision" key={`${decision.kind}:${decision.sourceId}:${decision.targetId}:${index}`}><span>{decision.detail}</span><select value={decision.kind === 'duplicate-channel' ? decisions.channelDuplicate || '' : decision.kind === 'preset-key-conflict' ? decisions.presetKeyConflict || '' : decision.kind === 'pinned-fingerprint' && inclusion.fingerprint ? 'include' : decisions.connectionDuplicate || ''} onChange={(event) => updateDecision(decision.kind, event.target.value)}><option value="">Choose resolution</option>{decision.choices.map((choice) => <option value={choice} key={choice}>{choice === 'reuse' ? 'Reuse target' : choice === 'add' ? 'Add as a new item' : choice === 'replace' ? 'Replace target channel' : choice === 'skip' ? 'Skip imported channel' : choice === 'unassign' ? 'Unassign imported key' : choice === 'unused' ? 'Leave key unused' : 'Include fingerprint'}</option>)}</select></label>)}</div>}</section>}
      </div>
      <div className="dialog-footer"><span className="muted">{decisionCount > 0 ? `${decisionCount} conflict${decisionCount === 1 ? '' : 's'} require explicit resolution.` : 'Cancel leaves the draft unchanged.'}</span><div className="dialog-actions"><button type="button" className="quiet-button" onClick={onClose} disabled={busy}>Cancel</button><button type="button" className="quiet-button" onClick={() => void runPreview()} disabled={busy || !sourceReady}>{busy ? 'Previewing...' : 'Preview merge'}</button><button type="button" className="primary-button" onClick={() => void apply()} disabled={busy || !preview?.canApply}>{busy ? 'Applying...' : 'Apply to draft'}<Check size={15} /></button></div></div>
    </section>
  </div>;
}
