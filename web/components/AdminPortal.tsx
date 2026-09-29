'use client';

import {
  Activity, AlertCircle, Check, ChevronRight, LayoutDashboard,
  Library, LogOut, Plus, RadioTower, RefreshCw, Save, Search, Settings2, ShieldCheck, Trash2,
  X
} from 'lucide-react';
import { FormEvent, useEffect, useMemo, useState } from 'react';
import type { DeviceDeliveryStats, DeviceSummary, MinimumConfig, PendingDeviceRequestSummary } from '@/lib/types';
import { applyModelProfile, emptyConfig } from '@/lib/default-config';
import { MODEL_PROFILES, type ModelProfile } from '@/lib/model-profiles';
import { filterDevices } from '@/lib/device-search';
import { ConfigEditorForm } from './ConfigEditorForm';
import { ActivityView } from './ActivityView';
import { OverviewView } from './OverviewView';
import { ConfigurationLibraryView } from './ConfigurationLibraryView';
import { ConfigImportDialog } from './ConfigImportDialog';

type SessionState = {
  loading: boolean;
  configured: boolean;
  authenticated: boolean;
  username: string | null;
};

type DeviceRecord = {
  deviceId: string;
  label: string;
  model: ModelProfile;
  config: MinimumConfig;
  createdAt: string;
  updatedAt: string;
  deliveryStats?: DeviceDeliveryStats;
};

type Notice = { tone: 'success' | 'error' | 'info'; text: string } | null;

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

function Field({ label, value, onChange, type = 'text', placeholder, autoComplete }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  placeholder?: string;
  autoComplete?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} type={type} placeholder={placeholder} autoComplete={autoComplete} />
    </label>
  );
}

function ModelProfileField({ value, onChange }: { value: ModelProfile; onChange: (value: ModelProfile) => void }) {
  return (
    <label className="field">
      <span>Model profile</span>
      <select value={value} onChange={(event) => onChange(event.target.value as ModelProfile)}>
        {MODEL_PROFILES.map((profile) => <option key={profile.value} value={profile.value}>{profile.label}</option>)}
      </select>
    </label>
  );
}

function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? 'brand-compact' : ''}`}>
      <img src="/minimum-logo.svg" alt="Minimum" />
      {!compact && <span><strong>MINIMUM</strong><small>ADMIN CONSOLE</small></span>}
    </div>
  );
}

function NoticeBar({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  if (!notice) return null;
  return (
    <div className={`notice notice-${notice.tone}`} role="status">
      {notice.tone === 'success' ? <Check size={16} /> : <AlertCircle size={16} />}
      <span>{notice.text}</span>
      <button className="icon-button notice-close" type="button" onClick={onClose} aria-label="Dismiss notice" title="Dismiss"><X size={15} /></button>
    </div>
  );
}

function SetupView({ onReady }: { onReady: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      await api('/api/setup', { method: 'POST', body: JSON.stringify({ username, password, confirmation }) });
      onReady();
    } catch (err) { setError(err instanceof Error ? err.message : 'Setup failed'); }
    finally { setBusy(false); }
  }
  return (
    <main className="auth-page">
      <div className="auth-grid-line" />
      <section className="auth-card">
        <Logo />
        <div className="auth-heading"><span className="eyebrow">FIRST-RUN SETUP</span><h1>Create administrator</h1><p>Secure the configuration console before registering devices.</p></div>
        <form onSubmit={submit} className="stack-form">
          <Field label="Username" value={username} onChange={setUsername} autoComplete="username" placeholder="operator" />
          <Field label="Password" value={password} onChange={setPassword} type="password" autoComplete="new-password" placeholder="12 characters minimum" />
          <Field label="Confirm password" value={confirmation} onChange={setConfirmation} type="password" autoComplete="new-password" placeholder="Repeat password" />
          {error && <div className="form-error"><AlertCircle size={15} />{error}</div>}
          <button className="primary-button full-width" type="submit" disabled={busy}>{busy ? 'Creating account...' : 'Create administrator'}<ChevronRight size={17} /></button>
        </form>
        <div className="auth-footer"><ShieldCheck size={15} /> Credentials are stored as a one-way hash.</div>
      </section>
    </main>
  );
}

function LoginView({ onReady }: { onReady: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try { await api('/api/login', { method: 'POST', body: JSON.stringify({ username, password }) }); onReady(); }
    catch (err) { setError(err instanceof Error ? err.message : 'Login failed'); }
    finally { setBusy(false); }
  }
  return (
    <main className="auth-page">
      <div className="auth-grid-line" />
      <section className="auth-card">
        <Logo />
        <div className="auth-heading"><span className="eyebrow">SECURE ACCESS</span><h1>Sign in</h1><p>Manage Minimum device configurations.</p></div>
        <form onSubmit={submit} className="stack-form">
          <Field label="Username" value={username} onChange={setUsername} autoComplete="username" placeholder="operator" />
          <Field label="Password" value={password} onChange={setPassword} type="password" autoComplete="current-password" placeholder="Your password" />
          {error && <div className="form-error"><AlertCircle size={15} />{error}</div>}
          <button className="primary-button full-width" type="submit" disabled={busy}>{busy ? 'Verifying...' : 'Sign in'}<ChevronRight size={17} /></button>
        </form>
        <div className="auth-footer"><ShieldCheck size={15} /> Session expires after eight hours.</div>
      </section>
    </main>
  );
}

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return <div className="empty-state"><div className="empty-icon"><RadioTower size={25} /></div><h2>No devices registered</h2><p>Provision a device profile to start publishing configuration.</p><button className="primary-button" type="button" onClick={onAdd}><Plus size={17} /> Add device</button></div>;
}

function PendingRequests({ requests, onRegister, onDismiss }: {
  requests: PendingDeviceRequestSummary[];
  onRegister: (deviceId: string) => void;
  onDismiss: (deviceId: string) => Promise<void>;
}) {
  const [dismissingDeviceIds, setDismissingDeviceIds] = useState<Set<string>>(new Set());

  async function dismiss(deviceId: string) {
    if (dismissingDeviceIds.has(deviceId)) return;
    if (!window.confirm(`Remove pending request for ${deviceId}? Only the pending request will be removed. No registered Device Profile will be changed.`)) return;
    setDismissingDeviceIds((current) => new Set(current).add(deviceId));
    try { await onDismiss(deviceId); }
    finally {
      setDismissingDeviceIds((current) => {
        const next = new Set(current);
        next.delete(deviceId);
        return next;
      });
    }
  }

  if (!requests.length) return null;
  return (
    <section className="pending-requests" aria-labelledby="pending-requests-heading">
      <div className="pending-heading">
        <div><span className="eyebrow">INBOUND REQUESTS</span><h3 id="pending-requests-heading">Awaiting registration <span className="heading-count">{requests.length.toString().padStart(2, '0')}</span></h3></div>
        <span className="muted">Last 24 hours</span>
      </div>
      <div className="pending-list">
        {requests.map((request) => (
          <div className="pending-row" key={request.deviceId}>
            <span className="device-mark"><RadioTower size={15} /></span>
            <span className="pending-identity"><strong>{request.deviceId}</strong><small>{request.requestCount} request{request.requestCount === 1 ? '' : 's'} · {formatDate(request.lastSeenAt)}</small></span>
            <span className="pending-actions">
              <button className="quiet-button" type="button" onClick={() => onRegister(request.deviceId)}>Register <ChevronRight size={15} /></button>
              <button className="danger-button" type="button" onClick={() => void dismiss(request.deviceId)} disabled={dismissingDeviceIds.has(request.deviceId)} aria-label={`Dismiss pending request for ${request.deviceId}`}>
                {dismissingDeviceIds.has(request.deviceId) ? 'Dismissing...' : 'Dismiss'} <X size={15} aria-hidden="true" />
              </button>
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

export function AdminPortal() {
  const [session, setSession] = useState<SessionState>({ loading: true, configured: false, authenticated: false, username: null });
  const [notice, setNotice] = useState<Notice>(null);
  const refreshSession = async () => {
    try { const data = await api<Omit<SessionState, 'loading'>>('/api/session'); setSession({ loading: false, ...data }); }
    catch { setSession((current) => ({ ...current, loading: false })); }
  };
  useEffect(() => { void refreshSession(); }, []);
  if (session.loading) return <div className="boot-screen"><Logo compact /><span>Checking console...</span></div>;
  if (!session.configured) return <SetupView onReady={refreshSession} />;
  if (!session.authenticated) return <LoginView onReady={refreshSession} />;
  return <Dashboard username={session.username || 'operator'} setNotice={setNotice} notice={notice} onLogout={refreshSession} />;
}

function Dashboard({ username, setNotice, notice, onLogout }: { username: string; setNotice: (notice: Notice) => void; notice: Notice; onLogout: () => void }) {
  const [devices, setDevices] = useState<DeviceSummary[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selected, setSelected] = useState<DeviceRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [prefillDeviceId, setPrefillDeviceId] = useState('');
  const [pendingRequests, setPendingRequests] = useState<PendingDeviceRequestSummary[]>([]);
  const [activeNav, setActiveNav] = useState('Devices');
  const visibleDevices = useMemo(() => filterDevices(devices, searchQuery), [devices, searchQuery]);
  const isSearching = searchQuery.trim().length > 0;
  const loadDevices = async () => {
    setLoading(true);
    try {
      const [data, pending] = await Promise.all([
        api<{ devices: DeviceSummary[] }>('/api/devices'),
        api<{ requests: PendingDeviceRequestSummary[] }>('/api/devices/pending').catch(() => ({ requests: [] }))
      ]);
      setDevices(data.devices);
      setPendingRequests(pending.requests);
      if (selectedId && !data.devices.some((device) => device.deviceId === selectedId)) { setSelectedId(null); setSelected(null); }
    } catch (err) { setNotice({ tone: 'error', text: err instanceof Error ? err.message : 'Could not load devices' }); }
    finally { setLoading(false); }
  };
  useEffect(() => { void loadDevices(); }, []);
  const chooseDevice = async (deviceId: string) => {
    setSelectedId(deviceId); setSelected(null);
    try { const data = await api<{ device: DeviceRecord }>(`/api/devices/${deviceId}`); setSelected(data.device); }
    catch (err) { setNotice({ tone: 'error', text: err instanceof Error ? err.message : 'Could not load device' }); }
  };
  const dismissPendingRequest = async (deviceId: string) => {
    try {
      const data = await api<{ ok: true; deviceId: string }>(`/api/devices/pending/${encodeURIComponent(deviceId)}`, { method: 'DELETE' });
      setPendingRequests((current) => current.filter((request) => request.deviceId !== deviceId));
      setNotice({ tone: 'success', text: `Pending request for ${data.deviceId} dismissed.` });
    } catch (err) {
      const detail = err instanceof Error ? err.message : 'The server could not remove it.';
      setNotice({ tone: 'error', text: `Could not dismiss pending request for ${deviceId}. ${detail} Try again.` });
    }
  };
  async function logout() { await api('/api/logout', { method: 'POST' }).catch(() => undefined); onLogout(); }
  return (
    <div className="portal-shell">
      <aside className="sidebar">
        <Logo />
        <div className="sidebar-label">WORKSPACE</div>
        <nav className="side-nav">
          <button className={activeNav === 'Overview' ? 'nav-item active' : 'nav-item'} onClick={() => setActiveNav('Overview')}><LayoutDashboard size={17} /> Overview</button>
          <button className={activeNav === 'Devices' ? 'nav-item active' : 'nav-item'} onClick={() => setActiveNav('Devices')}><RadioTower size={17} /> Devices <span className="nav-count">{devices.length}</span></button>
          <button className={activeNav === 'Library' ? 'nav-item active' : 'nav-item'} onClick={() => setActiveNav('Library')}><Library size={17} /> Library</button>
          <button className={activeNav === 'Activity' ? 'nav-item active' : 'nav-item'} onClick={() => setActiveNav('Activity')}><Activity size={17} /> Activity</button>
        </nav>
        <div className="sidebar-bottom">
          <div className="secure-chip"><ShieldCheck size={15} /><span><strong>Protected</strong><small>Admin session active</small></span></div>
          <button className="nav-item logout-item" onClick={logout}><LogOut size={17} /> Sign out</button>
        </div>
      </aside>
      <main className="main-content">
        <header className="topbar"><div><span className="breadcrumb">MINIMUM / {activeNav.toUpperCase()}</span><h1>{activeNav === 'Devices' ? 'Device registry' : activeNav === 'Library' ? 'Configuration library' : activeNav}</h1></div><div className="topbar-actions"><span className="kv-status">Storage status in Overview</span><span className="user-chip">{username.slice(0, 1).toUpperCase()}</span></div></header>
        <NoticeBar notice={notice} onClose={() => setNotice(null)} />
        {activeNav === 'Overview' && <OverviewView />}
        {activeNav === 'Library' && <ConfigurationLibraryView />}
        {activeNav === 'Activity' && <ActivityView />}
        {activeNav === 'Devices' && <section className="workspace-grid">
          <section className="registry-panel surface">
            <div className="panel-heading"><div><span className="eyebrow">CONFIG PROFILES</span><h2>Devices <span className="heading-count">{devices.length.toString().padStart(2, '0')}</span></h2></div><div className="panel-actions"><button className="icon-button" onClick={() => void loadDevices()} title="Refresh devices" aria-label="Refresh devices"><RefreshCw size={17} /></button><button className="primary-button" onClick={() => setShowAdd(true)}><Plus size={17} /> Add device</button></div></div>
            <PendingRequests requests={pendingRequests} onRegister={(deviceId) => { setPrefillDeviceId(deviceId); setShowAdd(true); }} onDismiss={dismissPendingRequest} />
            {showAdd && <AddDeviceForm key={prefillDeviceId || 'new'} initialDeviceId={prefillDeviceId} onClose={() => { setShowAdd(false); setPrefillDeviceId(''); }} onSaved={(device) => { setShowAdd(false); setPrefillDeviceId(''); setNotice({ tone: 'success', text: `Device ${device.deviceId} created. It can now fetch configuration by Device ID.` }); void loadDevices(); setSelectedId(device.deviceId); setSelected({ ...device, config: emptyConfig(device.deviceId, device.model) }); }} />}
            {!loading && devices.length > 0 && <div className="registry-search">
              <label className="search-field">
                <span className="sr-only">Search devices by Device ID or display label</span>
                <Search size={16} aria-hidden="true" />
                <input
                  type="search"
                  value={searchQuery}
                  onChange={(event) => setSearchQuery(event.target.value)}
                  placeholder="Search Device ID or display label"
                  autoComplete="off"
                />
                {isSearching && <button type="button" className="search-clear" onClick={() => setSearchQuery('')} aria-label="Clear device search" title="Clear search"><X size={15} /></button>}
              </label>
              <span className="search-summary" aria-live="polite">{isSearching ? `${visibleDevices.length} of ${devices.length}` : `${devices.length} total`}</span>
            </div>}
            {loading ? <div className="loading-row"><RefreshCw size={17} className="spin" />Loading registry...</div> : devices.length === 0 ? <EmptyState onAdd={() => setShowAdd(true)} /> : visibleDevices.length === 0 ? <div className="search-empty"><Search size={24} /><h3>No matching devices</h3><p>Try a different Device ID or display label.</p><button className="quiet-button" type="button" onClick={() => setSearchQuery('')}>Clear search</button></div> : <div className="device-table"><div className="table-head"><span>PROFILE</span><span>MODEL</span><span>VERSION</span><span>UPDATED</span><span /></div>{visibleDevices.map((device) => <button className={`device-row ${selectedId === device.deviceId ? 'selected' : ''}`} key={device.deviceId} onClick={() => void chooseDevice(device.deviceId)}><span className="device-identity"><span className="device-mark"><RadioTower size={16} /></span><span><strong>{device.deviceId}</strong><small>{device.label}</small></span></span><span className="muted mono">{device.model}</span><span className="version-pill">v{device.configVersion}</span><span className="muted">{formatDate(device.updatedAt)}</span><ChevronRight size={16} className="row-chevron" /></button>)}</div>}
          </section>
          {selectedId && <DeviceEditor key={selectedId} device={selected} onSaved={(updated) => { setSelected(updated); setNotice({ tone: 'success', text: `${updated.deviceId} saved at config v${updated.config.configVersion}.` }); void loadDevices(); }} onDeleted={() => { setSelected(null); setSelectedId(null); setNotice({ tone: 'success', text: 'Device removed.' }); void loadDevices(); }} />}
        </section>}
      </main>
    </div>
  );
}

function AddDeviceForm({ initialDeviceId = '', onClose, onSaved }: { initialDeviceId?: string; onClose: () => void; onSaved: (device: DeviceRecord) => void }) {
  const [deviceId, setDeviceId] = useState('');
  const [label, setLabel] = useState('');
  const [model, setModel] = useState<ModelProfile>('generic-radio');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { setDeviceId(initialDeviceId); }, [initialDeviceId]);
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const normalized = deviceId.toUpperCase();
      const data = await api<{ device: DeviceSummary }>('/api/devices', { method: 'POST', body: JSON.stringify({ deviceId: normalized, label, model, config: emptyConfig(normalized, model) }) });
      onSaved({ ...data.device, config: emptyConfig(normalized, model) });
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not create device'); }
    finally { setBusy(false); }
  }
  return <div className="inline-form"><div className="inline-form-head"><div><span className="eyebrow">NEW PROFILE</span><h3>Register device</h3></div><button className="icon-button" onClick={onClose} aria-label="Close" title="Close"><X size={17} /></button></div><form onSubmit={submit} className="form-grid"><Field label="Device ID" value={deviceId} onChange={setDeviceId} placeholder="ABC123" /><Field label="Display label" value={label} onChange={setLabel} placeholder="Field unit 01" /><ModelProfileField value={model} onChange={setModel} /><div className="form-actions"><button className="quiet-button" type="button" onClick={onClose}>Cancel</button><button className="primary-button" type="submit" disabled={busy}>{busy ? 'Creating...' : 'Create profile'}<Plus size={16} /></button></div>{error && <div className="form-error grid-span"><AlertCircle size={15} />{error}</div>}</form></div>;
}

function DeviceEditor({ device, onSaved, onDeleted }: { device: DeviceRecord | null; onSaved: (device: DeviceRecord) => void; onDeleted: () => void }) {
  const [label, setLabel] = useState('');
  const [model, setModel] = useState<ModelProfile>('generic-radio');
  const [draft, setDraft] = useState<MinimumConfig | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [importMode, setImportMode] = useState<'preset' | 'device' | null>(null);
  useEffect(() => { if (device) { setLabel(device.label); setModel(device.model); setDraft(device.config); setError(''); } }, [device]);
  if (!device) return <section className="editor-panel surface loading-editor"><RefreshCw size={17} className="spin" />Loading profile...</section>;
  const currentDevice = device;
  function handleModelChange(nextModel: ModelProfile) {
    setModel(nextModel);
    setDraft((current) => current ? applyModelProfile(current, nextModel) : emptyConfig(currentDevice.deviceId, nextModel));
  }
  async function save() {
    if (!draft) return;
    setBusy(true); setError('');
    try { const data = await api<{ device: DeviceRecord }>(`/api/devices/${currentDevice.deviceId}`, { method: 'PATCH', body: JSON.stringify({ label, model, config: draft }) }); onSaved(data.device); }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not save profile'); }
    finally { setBusy(false); }
  }
  async function remove() {
    if (!window.confirm(`Delete ${currentDevice.deviceId}? This cannot be undone.`)) return;
    try { await api(`/api/devices/${currentDevice.deviceId}`, { method: 'DELETE' }); onDeleted(); }
    catch (err) { setError(err instanceof Error ? err.message : 'Could not delete device'); }
  }
  const delivery = currentDevice.deliveryStats;
  return <section className="editor-panel surface"><div className="editor-heading"><div className="editor-title"><span className="device-mark large"><RadioTower size={19} /></span><div><span className="eyebrow">DEVICE PROFILE</span><h2>{device.deviceId}</h2><span className="muted">{device.label}</span></div></div><div className="editor-actions"><span className="version-pill">v{device.config.configVersion}</span><button className="icon-button danger-icon" onClick={() => void remove()} title="Delete device" aria-label="Delete device"><Trash2 size={17} /></button></div></div><div className="editor-meta"><span><small>MODEL</small><strong>{model}</strong></span><span><small>LAST UPDATED</small><strong>{formatDate(device.updatedAt)}</strong></span><span><small>DEVICE API</small><strong className="green-text">Device ID lookup</strong></span></div><div className="device-delivery-meta" aria-label="Configuration request and served metadata"><span><small>FIRST SUCCESSFUL REQUEST</small><strong>{delivery?.firstRequestAt ? formatDate(delivery.firstRequestAt) : '—'}</strong></span><span><small>LAST SUCCESSFUL REQUEST</small><strong>{delivery?.lastRequestAt ? formatDate(delivery.lastRequestAt) : '—'}</strong></span><span><small>LAST CONFIG VERSION SERVED</small><strong>{typeof delivery?.lastConfigVersionServed === 'number' ? `v${delivery.lastConfigVersionServed}` : 'Unknown'}</strong></span><span><small>CURRENT STORED VERSION</small><strong>v{currentDevice.config.configVersion}</strong></span><span><small>TOTAL SUCCESSFUL REQUESTS</small><strong>{delivery?.servedCount ?? 0}</strong></span></div><div className="editor-tabs"><span className="editor-tab active"><Settings2 size={15} /> Configuration</span></div><div className="config-editor"><div className="form-grid editor-basics"><Field label="Display label" value={label} onChange={setLabel} /><ModelProfileField value={model} onChange={handleModelChange} /></div>{draft && <ConfigEditorForm config={draft} model={model} onChange={setDraft} onOpenImport={setImportMode} />}{error && <div className="form-error"><AlertCircle size={15} />{error}</div>}<div className="editor-footer"><span className="muted">Effective changes update the version automatically. Registered radios fetch updates automatically.</span><button className="primary-button" onClick={() => void save()} disabled={busy || !draft}><Save size={16} />{busy ? 'Saving...' : 'Save configuration'}</button></div></div>{draft && <ConfigImportDialog open={importMode !== null} initialMode={importMode || 'preset'} targetDeviceId={currentDevice.deviceId} targetDraft={draft} onClose={() => setImportMode(null)} onApplied={(nextDraft) => { setDraft(nextDraft); setImportMode(null); setError(''); }} />}</section>;
}
