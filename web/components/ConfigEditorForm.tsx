'use client';

import {
  Check, ChevronDown, ChevronUp, Copy, Eye, EyeOff, Info, Library, Plus, Settings2, Trash2
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import type { MinimumConfig } from '@/lib/types';
import type { ModelProfile } from '@/lib/model-profiles';
import { calculateAprsPasscode, normalizeAprsCallsign } from '@/lib/aprs';

type Connection = {
  name?: string; host?: string; port?: number; username?: string; password?: string;
  serverCertificateSha256?: string; autoTrustServerCertificate?: boolean;
};
type Channel = {
  id: string; label: string; alias?: string; connectionId: string; path: string;
  presetKey?: string; access: { mode: 'none' | 'public' | 'protected'; token?: string; tokens?: string[]; tokenRef?: string };
};

type ConfigShape = MinimumConfig & {
  service?: { name?: string };
  radio?: { defaultChannel?: string; autoConnect?: boolean; autoReconnect?: boolean };
  connections?: Record<string, Connection>;
  channels?: Channel[];
  ui?: Record<string, unknown>;
  ptt?: { maximumTxSeconds?: number; allowScreenOff?: boolean; releaseOnNetworkLoss?: boolean };
  tracking?: { enabled?: boolean; pttTriggered?: boolean; aprs?: Record<string, unknown> };
  hardware?: Record<string, unknown>;
};

type Tab = 'general' | 'connections' | 'channels' | 'behavior' | 'tracking' | 'advanced';

function asConfig(value: MinimumConfig): ConfigShape { return value as ConfigShape; }
function nonBlank(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value : fallback;
}
function uniqueId(prefix: string, existing: string[]) {
  let index = existing.length + 1;
  while (existing.includes(`${prefix}-${index}`)) index += 1;
  return `${prefix}-${index}`;
}

function Field({ label, value, onChange, type = 'text', placeholder, help, disabled = false }: {
  label: string; value: string; onChange: (value: string) => void; type?: string; placeholder?: string; help?: string; disabled?: boolean;
}) {
  return <label className="field"><span>{label}</span><input value={value} onChange={(event) => onChange(event.target.value)} type={type} placeholder={placeholder} disabled={disabled} />{help && <small className="field-help">{help}</small>}</label>;
}

function NumberField({ label, value, onChange, min, max, help }: { label: string; value: number; onChange: (value: number) => void; min?: number; max?: number; help?: string }) {
  return <label className="field"><span>{label}</span><input value={Number.isFinite(value) ? value : ''} onChange={(event) => onChange(Number(event.target.value))} type="number" min={min} max={max} />{help && <small className="field-help">{help}</small>}</label>;
}

function Toggle({ label, checked, onChange, disabled = false, help }: { label: string; checked: boolean; onChange: (value: boolean) => void; disabled?: boolean; help?: string }) {
  return <label className={`toggle-field ${disabled ? 'is-disabled' : ''}`}><span><strong>{label}</strong>{help && <small>{help}</small>}</span><input type="checkbox" checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} /><i aria-hidden="true" /></label>;
}

function Section({ title, description, children, action }: { title: string; description: string; children: React.ReactNode; action?: React.ReactNode }) {
  return <section className="config-section"><div className="section-heading"><div><h3>{title}</h3><p>{description}</p></div>{action}</div>{children}</section>;
}

export function ConfigEditorForm({ config, model, onChange, onOpenImport }: { config: MinimumConfig; model: ModelProfile; onChange: (config: MinimumConfig) => void; onOpenImport?: (mode: 'preset' | 'device') => void }) {
  const value = asConfig(config);
  const [tab, setTab] = useState<Tab>('general');
  const [visibleSecrets, setVisibleSecrets] = useState<Record<string, boolean>>({});
  const [advancedText, setAdvancedText] = useState(() => JSON.stringify(config, null, 2));
  const [advancedError, setAdvancedError] = useState('');
  useEffect(() => { setAdvancedText(JSON.stringify(config, null, 2)); }, [config]);
  const connections = value.connections || {};
  const channels = value.channels || [];
  const connectionEntries = useMemo(() => Object.entries(connections), [connections]);

  function update(patch: Partial<ConfigShape>) { onChange({ ...value, ...patch } as MinimumConfig); }
  function updateNested<K extends keyof ConfigShape>(key: K, patch: Record<string, unknown>) {
    update({ [key]: { ...((value[key] || {}) as Record<string, unknown>), ...patch } } as Partial<ConfigShape>);
  }
  function updateConnection(id: string, patch: Partial<Connection>) {
    update({ connections: { ...connections, [id]: { ...connections[id], ...patch } } });
  }
  function updateChannel(index: number, patch: Partial<Channel>) {
    update({ channels: channels.map((channel, current) => current === index ? { ...channel, ...patch } : channel) });
  }
  function removeConnection(id: string) {
    const ids = Object.keys(connections);
    if (ids.length <= 1) return;
    const next = { ...connections }; delete next[id];
    const fallback = ids.find((candidate) => candidate !== id) || ids[0];
    update({ connections: next, channels: channels.map((channel) => channel.connectionId === id ? { ...channel, connectionId: fallback } : channel) });
  }
  function removeChannel(index: number) {
    if (channels.length <= 1) return;
    const next = channels.filter((_, current) => current !== index);
    const defaultChannel = value.radio?.defaultChannel === channels[index]?.id ? next[0].id : value.radio?.defaultChannel;
    update({ channels: next, radio: { ...value.radio, defaultChannel } });
  }
  function applyAdvanced() {
    try {
      const parsed = JSON.parse(advancedText) as MinimumConfig;
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Configuration must be an object');
      setAdvancedError(''); onChange(parsed);
    } catch (error) { setAdvancedError(error instanceof Error ? error.message : 'Invalid JSON'); }
  }

  const tabs: Array<[Tab, string]> = [
    ['general', 'General'], ['connections', 'Servers'], ['channels', 'Channels & default'],
    ['behavior', 'Radio behavior'], ['tracking', 'Location & APRS'], ['advanced', 'Advanced']
  ];
  return <div className="config-workbench">
    <div className="editor-tabs config-tabs" role="tablist">{tabs.map(([id, label]) => <button key={id} type="button" role="tab" aria-selected={tab === id} className={tab === id ? 'editor-tab active' : 'editor-tab'} onClick={() => setTab(id)}>{label}</button>)}</div>
    <div className="config-workbench-body">
      {onOpenImport && <Section title="Configuration library" description="Merge a reusable server into this unsaved draft. Device identity, model and policy stay local to this profile." action={<div className="library-entry-actions"><button type="button" className="quiet-button" onClick={() => onOpenImport('preset')}><Library size={15} /> Add from library</button><button type="button" className="quiet-button" onClick={() => onOpenImport('device')}><Copy size={15} /> Copy from device</button></div>}><div className="read-only-callout"><Info size={15} /><span>Preview shows connection and channel conflicts. Apply updates this draft only; use <strong>Save configuration</strong> to persist it.</span></div></Section>}
      {tab === 'general' && <Section title="Device identity" description="Basic information shown in the registry and on the radio."><div className="form-grid compact-grid"><Field label="Service name" value={nonBlank(value.service?.name, `Minimum ${model.toUpperCase()}`)} onChange={(name) => updateNested('service', { name })} help="A friendly name for this installation." /><Field label="Device ID" value={value.deviceId || ''} onChange={() => undefined} disabled help="The registry identity cannot be changed from a device profile." /></div><div className="read-only-callout"><Info size={15} /><span>Hardware profile <strong>{String(value.hardware?.profile || model)}</strong> is managed by the selected model.</span></div></Section>}

      {tab === 'connections' && <Section title="Server connections" description="Each server can have its own address, username and password." action={<button type="button" className="quiet-button" onClick={() => { const id = uniqueId('server', Object.keys(connections)); update({ connections: { ...connections, [id]: { name: 'New server', host: '', port: 64738, username: '', autoTrustServerCertificate: true } } }); }}><Plus size={15} /> Add server</button>}><div className="repeat-list">{connectionEntries.map(([id, connection], index) => <div className="repeat-card" key={id}><div className="repeat-card-heading"><div><span className="eyebrow">SERVER {index + 1}</span><strong>{id}</strong></div><button type="button" className="icon-button danger-icon" disabled={connectionEntries.length <= 1} onClick={() => removeConnection(id)} title="Remove server" aria-label={`Remove server ${id}`}><Trash2 size={15} /></button></div><div className="form-grid compact-grid"><Field label="Server name" value={connection.name || ''} onChange={(name) => updateConnection(id, { name })} placeholder="Operations server" /><Field label="Address" value={connection.host || ''} onChange={(host) => updateConnection(id, { host })} placeholder="voice.example.org" /><NumberField label="Port" value={connection.port || 64738} onChange={(port) => updateConnection(id, { port })} min={1} max={65535} /><Field label="Username" value={connection.username || ''} onChange={(username) => updateConnection(id, { username })} /><SecretField label="Server password" value={connection.password || ''} visible={Boolean(visibleSecrets[`server:${id}`])} onToggle={() => setVisibleSecrets((state) => ({ ...state, [`server:${id}`]: !state[`server:${id}`] }))} onChange={(password) => updateConnection(id, { password })} /><Field label="Certificate SHA-256 (optional)" value={connection.serverCertificateSha256 || ''} onChange={(serverCertificateSha256) => updateConnection(id, { serverCertificateSha256 })} help="Leave blank to use the server trust policy." /></div><Toggle label="Trust server certificate automatically" checked={connection.autoTrustServerCertificate !== false} onChange={(autoTrustServerCertificate) => updateConnection(id, { autoTrustServerCertificate })} /></div>)}</div></Section>}

      {tab === 'channels' && <Section title="Channels and default selection" description="Choose the configured fallback channel and edit the channels available on this device." action={<button type="button" className="quiet-button" onClick={() => { const id = uniqueId('channel', channels.map((channel) => channel.id)); const firstConnection = Object.keys(connections)[0] || 'server-1'; const next = [...channels, { id, label: 'New channel', alias: '', connectionId: firstConnection, path: '/', access: { mode: 'none' as const } }]; update({ channels: next }); }}><Plus size={15} /> Add channel</button>}><div className="default-channel-picker"><div className="default-channel-heading"><span className="eyebrow">RADIO DEFAULT</span><strong>Default channel</strong></div><label className="field"><span>Channel used when no previous selection is available</span><select aria-label="Default channel" value={value.radio?.defaultChannel || ''} onChange={(event) => updateNested('radio', { defaultChannel: event.target.value })}>{channels.map((channel) => <option key={channel.id} value={channel.id}>{channel.alias || channel.label} ({channel.id})</option>)}</select></label><small className="field-help"><code>radio.defaultChannel</code> is used for first connection or when the saved channel no longer exists. A valid last-selected channel still resumes after restart or reconnect.</small></div><div className="repeat-list">{channels.map((channel, index) => <div className={`repeat-card channel-card ${value.radio?.defaultChannel === channel.id ? 'is-default' : ''}`} key={`${channel.id}-${index}`}><div className="repeat-card-heading"><div><span className="eyebrow">CHANNEL {index + 1}</span><strong>{channel.alias || channel.label || channel.id}</strong></div><div className="reorder-actions">{value.radio?.defaultChannel === channel.id ? <span className="default-channel-badge"><Check size={13} /> Default</span> : <button type="button" className="set-default-button" onClick={() => updateNested('radio', { defaultChannel: channel.id })}>Set default</button>}<button type="button" className="icon-button" disabled={index === 0} onClick={() => { const next = [...channels]; [next[index - 1], next[index]] = [next[index], next[index - 1]]; update({ channels: next }); }} title="Move up" aria-label="Move channel up"><ChevronUp size={15} /></button><button type="button" className="icon-button" disabled={index === channels.length - 1} onClick={() => { const next = [...channels]; [next[index], next[index + 1]] = [next[index + 1], next[index]]; update({ channels: next }); }} title="Move down" aria-label="Move channel down"><ChevronDown size={15} /></button><button type="button" className="icon-button danger-icon" disabled={channels.length <= 1} onClick={() => removeChannel(index)} title="Remove channel" aria-label="Remove channel"><Trash2 size={15} /></button></div></div><div className="form-grid compact-grid"><Field label="Channel alias" value={channel.alias || ''} onChange={(alias) => updateChannel(index, { alias })} placeholder="Dispatch" help="Short name displayed prominently on Minimum." /><Field label="Channel label" value={channel.label || ''} onChange={(label) => updateChannel(index, { label })} placeholder="Operations" /><label className="field"><span>Server</span><select value={channel.connectionId} onChange={(event) => updateChannel(index, { connectionId: event.target.value })}>{Object.keys(connections).map((connectionId) => <option key={connectionId} value={connectionId}>{connections[connectionId].name || connectionId}</option>)}</select></label><Field label="Full channel path" value={channel.path || '/'} onChange={(path) => updateChannel(index, { path: path.startsWith('/') ? path : `/${path}` })} placeholder="/PUBLIC/MAIN" /><Field label="Preset key (optional)" value={channel.presetKey || ''} onChange={(presetKey) => updateChannel(index, { presetKey: presetKey.toUpperCase() })} placeholder="P1" help="Physical channel shortcut, such as P1 or P2." /><label className="field"><span>Access token</span><select value={channel.access?.mode === 'public' ? 'public' : 'none'} onChange={(event) => { const mode = event.target.value as 'none' | 'public'; updateChannel(index, { access: mode === 'none' ? { mode } : { mode, token: channel.access?.token || '' } }); }}><option value="none">No token</option><option value="public">Token in config</option></select></label>{channel.access?.mode === 'public' && <SecretField label="Channel access token" value={channel.access.token || ''} visible={Boolean(visibleSecrets[`channel:${channel.id}`])} onToggle={() => setVisibleSecrets((state) => ({ ...state, [`channel:${channel.id}`]: !state[`channel:${channel.id}`] }))} onChange={(token) => updateChannel(index, { access: { mode: 'public', token } })} help="Stored in this private device config and sent only to the selected server." />}</div></div>)}</div></Section>}

      {tab === 'behavior' && <Section title="Connection and PTT behavior" description="Set reconnect safety and the controls operators see on the device."><div className="toggle-list"><Toggle label="Connect automatically" checked={value.radio?.autoConnect === true} onChange={(autoConnect) => updateNested('radio', { autoConnect })} help="Connect to the saved channel when Minimum starts." /><Toggle label="Reconnect automatically" checked={value.radio?.autoReconnect !== false} onChange={(autoReconnect) => updateNested('radio', { autoReconnect })} help="Use the backoff policy after a network interruption." /><Toggle label="Allow screen-off PTT" checked={value.ptt?.allowScreenOff !== false} onChange={(allowScreenOff) => updateNested('ptt', { allowScreenOff })} help="Keep PTT available while the display is off." /></div><div className="form-grid compact-grid"><NumberField label="Maximum transmit time (seconds)" value={value.ptt?.maximumTxSeconds || 120} onChange={(maximumTxSeconds) => updateNested('ptt', { maximumTxSeconds })} min={1} max={120} help="The service releases PTT when this limit is reached." /></div><div className="read-only-callout"><Settings2 size={15} /><span>Network-loss PTT release is always enabled for safety.</span></div></Section>}

      {tab === 'tracking' && <Section title="Location and APRS" description="Only devices with a supported location service can publish tracking data.">{<div className="tracking-support"><span className={`status-dot ${value.hardware?.locationTrackingSupported ? '' : 'status-dot-off'}`} /><span>{value.hardware?.locationTrackingSupported ? `Location service available for ${model.toUpperCase()}` : `Location tracking is not available on ${model.toUpperCase()}`}</span></div>}<div className="toggle-list"><Toggle label="Enable location tracking" checked={value.tracking?.enabled === true} disabled={value.hardware?.locationTrackingSupported !== true} onChange={(enabled) => updateNested('tracking', { enabled })} help="Publish health and position only when the hardware reports a fix." /><Toggle label="Send a position after PTT" checked={value.tracking?.pttTriggered !== false} disabled={value.hardware?.locationTrackingSupported !== true} onChange={(pttTriggered) => updateNested('tracking', { pttTriggered })} /></div><AprsFields value={value} disabled={value.tracking?.enabled !== true || value.hardware?.locationTrackingSupported !== true} visible={Boolean(visibleSecrets.aprs)} onToggleSecret={() => setVisibleSecrets((state) => ({ ...state, aprs: !state.aprs }))} onChange={updateNested} /></Section>}

      {tab === 'advanced' && <Section title="Advanced JSON" description="Use this only for fields not exposed above. The normal editor is safer for everyday changes."><div className="advanced-warning"><Info size={16} /><span>Manual JSON is validated when you apply it. Credentials and tokens remain hidden elsewhere in the console.</span></div><label className="field json-field"><span>Schema 3 configuration</span><textarea value={advancedText} onChange={(event) => { setAdvancedText(event.target.value); setAdvancedError(''); }} spellCheck={false} /></label>{advancedError && <div className="form-error">{advancedError}</div>}<div className="editor-footer"><span className="muted">Applying JSON replaces the form values.</span><button type="button" className="quiet-button" onClick={applyAdvanced}>Apply JSON</button></div></Section>}
    </div>
  </div>;
}

function SecretField({ label, value, visible, onToggle, onChange, help }: { label: string; value: string; visible: boolean; onToggle: () => void; onChange: (value: string) => void; help?: string }) {
  return <label className="field secret-field"><span>{label}</span><span className="secret-input"><input value={value} onChange={(event) => onChange(event.target.value)} type={visible ? 'text' : 'password'} autoComplete="new-password" /><button type="button" className="icon-button" onClick={onToggle} title={visible ? 'Hide value' : 'Show value'} aria-label={visible ? 'Hide value' : 'Show value'}>{visible ? <EyeOff size={15} /> : <Eye size={15} />}</button></span>{help && <small className="field-help">{help}</small>}</label>;
}

function AprsFields({ value, disabled, visible, onToggleSecret, onChange }: { value: ConfigShape; disabled: boolean; visible: boolean; onToggleSecret: () => void; onChange: (key: 'tracking', patch: Record<string, unknown>) => void }) {
  const aprs = (value.tracking?.aprs || {}) as Record<string, unknown>;
  const setAprs = (patch: Record<string, unknown>) => onChange('tracking', { ...value.tracking, aprs: { ...aprs, ...patch } });
  const sourceCallsign = normalizeAprsCallsign(String(aprs.sourceCallsign || ''));
  return <div className={`aprs-fields ${disabled ? 'is-disabled' : ''}`}><fieldset disabled={disabled}><div className="subsection-heading"><strong>APRS publishing</strong><small>Position objects default to VR- plus the Device ID when no name is set.</small></div><Toggle label="Enable APRS publishing" checked={aprs.enabled === true} disabled={disabled} onChange={(enabled) => setAprs({ enabled })} help="Send position packets to APRS-IS when location tracking is enabled." /><div className="form-grid compact-grid"><Field label="Source callsign" value={sourceCallsign} onChange={(nextValue) => { const nextCallsign = normalizeAprsCallsign(nextValue); setAprs({ sourceCallsign: nextCallsign, passcode: calculateAprsPasscode(nextCallsign) }); }} /><Field label="APRS passcode" value={calculateAprsPasscode(sourceCallsign)} onChange={() => undefined} disabled help="Calculated automatically from the source callsign." /><Field label="Object name (optional)" value={String(aprs.objectName || '')} onChange={(objectName) => setAprs({ objectName: objectName.toUpperCase() })} help="Up to 9 characters. Leave blank to use the Device ID." /><Field label="APRS-IS host" value={String(aprs.host || 'ametx.com')} onChange={(host) => setAprs({ host })} /><NumberField label="APRS-IS port" value={Number(aprs.port || 8888)} onChange={(port) => setAprs({ port })} min={1} max={65535} /></div></fieldset></div>;
}
