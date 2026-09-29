'use client';

import { AlertCircle, Copy, Edit3, Library, Plus, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import type { SafeConfigPreset, StoredConfigPreset } from '@/lib/config-library-types';
import type { ConfigPresetSecretUpdate } from '@/lib/config-preset-storage';
import { ConfigPresetDialog } from './ConfigPresetDialog';

type SafePresetRecord = SafeConfigPreset & { createdAt: string; updatedAt: string };

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: { ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...(init?.headers || {}) },
    cache: 'no-store'
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(typeof data?.error === 'string' ? data.error : 'Request failed');
  return data as T;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function safeSecretLabel(present: boolean) {
  return present ? '••••••••' : 'Not stored';
}

export function ConfigurationLibraryView() {
  const [presets, setPresets] = useState<SafePresetRecord[]>([]);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<SafePresetRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [dialog, setDialog] = useState<'create' | 'edit' | 'duplicate' | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  async function load(search = query) {
    setLoading(true); setError('');
    try {
      const data = await api<{ presets: SafePresetRecord[] }>(`/api/config-presets${search.trim() ? `?q=${encodeURIComponent(search.trim())}` : ''}`);
      setPresets(data.presets || []);
      if (selectedId && !(data.presets || []).some((preset) => preset.id === selectedId)) { setSelectedId(null); setSelected(null); }
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not load configuration library'); }
    finally { setLoading(false); }
  }

  useEffect(() => { void load(''); }, []);

  async function choose(id: string) {
    setSelectedId(id); setDetailLoading(true); setError('');
    try { const data = await api<{ preset: SafePresetRecord }>(`/api/config-presets/${encodeURIComponent(id)}`); setSelected(data.preset); }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not load preset detail'); }
    finally { setDetailLoading(false); }
  }

  async function savePreset(preset: StoredConfigPreset, secretPolicy?: ConfigPresetSecretUpdate) {
    const editing = dialog === 'edit';
    const duplicateSource = dialog === 'duplicate' ? selected?.id : undefined;
    if (duplicateSource && selected) {
      const data = await api<{ preset: SafePresetRecord }>(`/api/config-presets/${encodeURIComponent(duplicateSource)}/duplicate`, {
        method: 'POST',
        body: JSON.stringify({ newId: preset.id, newName: preset.name, expectedUpdatedAt: selected.updatedAt })
      });
      setNotice('Preset duplicated.'); setDialog(null); setSelectedId(data.preset.id); setSelected(data.preset); await load(); return;
    }
    const editId = editing ? selected?.id : undefined;
    const payloadPreset = editId ? { ...preset, id: editId } : preset;
    const url = editId ? `/api/config-presets/${encodeURIComponent(editId)}` : '/api/config-presets';
    const data = await api<{ preset: SafePresetRecord }>(url, {
      method: editing ? 'PATCH' : 'POST',
      body: JSON.stringify({ preset: payloadPreset, ...(editing && selected?.updatedAt ? { expectedUpdatedAt: selected.updatedAt } : {}), ...(editing && secretPolicy ? { secretPolicy } : {}) })
    });
    setNotice(editing ? 'Preset updated.' : duplicateSource ? 'Preset duplicated.' : 'Preset created.');
    setDialog(null); setSelectedId(data.preset.id); setSelected(data.preset);
    await load();
  }

  async function remove() {
    if (!selected) return;
    if (!window.confirm(`Delete preset ${selected.name}? This cannot be undone.`)) return;
    setBusyId(selected.id); setError('');
    try {
      await api(`/api/config-presets/${encodeURIComponent(selected.id)}?expectedUpdatedAt=${encodeURIComponent(selected.updatedAt)}`, { method: 'DELETE' });
      setNotice('Preset deleted.'); setSelected(null); setSelectedId(null); await load();
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not delete preset'); }
    finally { setBusyId(null); }
  }

  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    if (!needle) return presets;
    return presets.filter((preset) => [preset.id, preset.name, preset.connection.name || '', preset.connection.host, ...preset.channels.flatMap((channel) => [channel.id, channel.label, channel.alias || '', channel.path])].join(' ').toLocaleLowerCase().includes(needle));
  }, [presets, query]);

  return <div className="library-view">
    <section className="library-panel surface">
      <div className="panel-heading"><div><span className="eyebrow">REUSABLE CONFIGURATION</span><h2>Configuration library <span className="heading-count">{presets.length.toString().padStart(2, '0')}</span></h2></div><div className="panel-actions"><button type="button" className="icon-button" onClick={() => void load()} disabled={loading} aria-label="Refresh configuration library" title="Refresh library"><RefreshCw size={17} className={loading ? 'spin' : ''} /></button><button type="button" className="primary-button" onClick={() => setDialog('create')}><Plus size={16} /> New preset</button></div></div>
      <div className="library-intro"><Library size={18} /><span>Presets contain one server and reusable channels. Device identity, model, PTT and tracking policy stay out of the library.</span></div>
      <div className="registry-search"><label className="search-field"><span className="sr-only">Search presets</span><Search size={16} aria-hidden="true" /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void load(event.currentTarget.value); }} placeholder="Search preset name, ID or server" autoComplete="off" />{query && <button type="button" className="search-clear" onClick={() => { setQuery(''); void load(''); }} aria-label="Clear preset search" title="Clear search"><X size={15} /></button>}</label><span className="search-summary" aria-live="polite">{visible.length} of {presets.length}</span></div>
      {error && <div className="form-error library-error"><AlertCircle size={15} />{error}<button type="button" className="quiet-button" onClick={() => void load()}>Retry</button></div>}
      {notice && <div className="library-notice" role="status">{notice}<button type="button" className="icon-button" onClick={() => setNotice('')} aria-label="Dismiss notice" title="Dismiss"><X size={14} /></button></div>}
      {loading ? <div className="loading-row"><RefreshCw size={17} className="spin" />Loading configuration library...</div> : visible.length === 0 ? <div className="empty-state library-empty"><div className="empty-icon"><Library size={24} /></div><h2>{query ? 'No matching presets' : 'No presets yet'}</h2><p>{query ? 'Try another name, ID or server address.' : 'Create a reusable server configuration for your devices.'}</p>{query ? <button type="button" className="quiet-button" onClick={() => { setQuery(''); void load(''); }}>Clear search</button> : <button type="button" className="primary-button" onClick={() => setDialog('create')}><Plus size={16} /> Create first preset</button>}</div> : <div className="library-list">{visible.map((preset) => <button type="button" className={`library-row ${selectedId === preset.id ? 'selected' : ''}`} key={preset.id} onClick={() => void choose(preset.id)}><span className="library-row-icon"><Library size={16} /></span><span className="library-row-main"><strong>{preset.name}</strong><small><code>{preset.id}</code> · {preset.connection.host}:{preset.connection.port}</small></span><span className="library-row-meta"><span>{preset.channels.length} channel{preset.channels.length === 1 ? '' : 's'}</span><small>{formatDate(preset.updatedAt)}</small></span></button>)}</div>}
    </section>
    <section className="library-detail surface">
      {!selectedId ? <div className="empty-state detail-empty"><div className="empty-icon"><Library size={24} /></div><h2>Select a preset</h2><p>Review masked details, edit or duplicate a reusable configuration.</p></div> : detailLoading || !selected ? <div className="loading-row"><RefreshCw size={17} className="spin" />Loading preset detail...</div> : <><div className="panel-heading"><div><span className="eyebrow">PRESET DETAIL</span><h2>{selected.name}</h2><small className="muted"><code>{selected.id}</code> · updated {formatDate(selected.updatedAt)}</small></div><div className="panel-actions"><button type="button" className="quiet-button" onClick={() => setDialog('duplicate')}><Copy size={15} /> Duplicate</button><button type="button" className="quiet-button" onClick={() => setDialog('edit')}><Edit3 size={15} /> Edit</button><button type="button" className="danger-button" onClick={() => void remove()} disabled={busyId === selected.id}><Trash2 size={15} />{busyId === selected.id ? 'Deleting...' : 'Delete'}</button></div></div><div className="library-detail-body"><div className="library-summary-grid"><div><span className="eyebrow">SERVER</span><strong>{selected.connection.name || selected.connection.id}</strong><small>{selected.connection.host}:{selected.connection.port}</small></div><div><span className="eyebrow">CHANNELS</span><strong>{selected.channels.length}</strong><small>Reusable paths</small></div><div><span className="eyebrow">SECRETS</span><strong>{[selected.connection.sensitive.username, selected.connection.sensitive.password, selected.connection.sensitive.fingerprint, ...selected.channels.map((channel) => channel.access.sensitive.publicTokens || channel.access.sensitive.protectedTokenRef)].filter(Boolean).length}</strong><small>Available, always masked</small></div></div><section className="library-detail-section"><div className="section-heading"><div><h3>Connection fields</h3><p>Safe detail never includes secret values.</p></div></div><dl className="library-fields"><div><dt>Address</dt><dd><code>{selected.connection.host}:{selected.connection.port}</code></dd></div><div><dt>Username</dt><dd>{safeSecretLabel(selected.connection.sensitive.username)}</dd></div><div><dt>Password</dt><dd>{safeSecretLabel(selected.connection.sensitive.password)}</dd></div><div><dt>Certificate fingerprint</dt><dd>{safeSecretLabel(selected.connection.sensitive.fingerprint)}</dd></div></dl></section><section className="library-detail-section"><div className="section-heading"><div><h3>Channels</h3><p>Device-specific policy is not part of this list.</p></div></div><div className="library-channel-list">{selected.channels.map((channel) => <div className="library-channel-row" key={channel.id}><span><strong>{channel.alias || channel.label}</strong><small><code>{channel.id}</code> · {channel.path}</small></span><span className="status-badge">{channel.access.mode === 'none' ? 'No token' : channel.access.mode === 'public' ? (channel.access.sensitive.publicTokens ? 'Token masked' : 'Public token omitted') : (channel.access.sensitive.protectedTokenRef ? 'Reference masked' : 'Reference omitted')}</span></div>)}</div></section></div></>}
    </section>
    <ConfigPresetDialog open={dialog !== null} mode={dialog || 'create'} preset={dialog === 'create' ? null : selected} onClose={() => setDialog(null)} onSaved={savePreset} />
  </div>;
}
