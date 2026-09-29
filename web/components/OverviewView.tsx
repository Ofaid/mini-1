'use client';

import { AlertCircle, Database, RefreshCw, TriangleAlert } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { ActivityEvent } from '@/lib/types';
import type { OverviewAttention, OverviewDevice, OverviewSummary } from '@/lib/overview';

function formatLocal(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function formatUtc(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}

function statusLabel(device: OverviewDevice) {
  if (device.status === 'never-fetched') return 'No successful fetch recorded';
  if (device.status === 'behind') return 'Older version served';
  if (device.status === 'stale') return 'No fetch in 7d';
  if (typeof device.lastServedVersion !== 'number') return 'Recent successful fetch; version unknown';
  return 'Recent successful fetch';
}

function attentionLabel(item: OverviewAttention) {
  if (item.reason === 'never-fetched') return 'Never fetched';
  if (item.reason === 'behind-version') return 'Older version served';
  if (item.reason === 'stale') return 'No fetch in 7d';
  if (item.reason === 'pending-repeated') return 'Repeated pending request';
  return 'Pending request';
}

function actionLabel(event: ActivityEvent) {
  return event.action.replaceAll('.', ' ');
}

type OverviewCardProps = { label: string; value: number; hint?: string; tone?: 'default' | 'attention' };

function OverviewCard({ label, value, hint, tone = 'default' }: OverviewCardProps) {
  return <article className={`overview-card ${tone === 'attention' ? 'overview-card-attention' : ''}`}><span>{label}</span><strong>{value}</strong>{hint && <small>{hint}</small>}</article>;
}

function DeviceStatus({ device }: { device: OverviewDevice }) {
  return <span className={`overview-status overview-status-${device.status}`}>{statusLabel(device)}</span>;
}

export function OverviewView() {
  const [data, setData] = useState<OverviewSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const response = await fetch('/api/overview', { cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(typeof body?.error === 'string' ? body.error : 'Could not load overview');
      setData(body as OverviewSummary);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load overview');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading && !data) return <section className="overview-view surface overview-state"><RefreshCw size={19} className="spin" /><span>Loading operational overview...</span></section>;
  if (error && !data) return <section className="overview-view surface overview-state overview-error"><AlertCircle size={20} /><div><h2>Overview unavailable</h2><p>{error}</p><button className="quiet-button" type="button" onClick={() => void load()}><RefreshCw size={15} /> Retry</button></div></section>;
  if (!data) return null;

  return <section className="overview-view">
    <div className="overview-toolbar"><div><span className="eyebrow">OPERATIONS</span><p>HTTP configuration delivery telemetry and administrator changes.</p></div><button className="icon-button" type="button" onClick={() => void load()} disabled={loading} aria-label="Refresh overview" title="Refresh overview"><RefreshCw size={17} className={loading ? 'spin' : ''} /></button></div>
    {error && <div className="form-error overview-inline-error"><AlertCircle size={15} />{error}<button className="quiet-button" type="button" onClick={() => void load()}>Retry</button></div>}
    <div className="overview-cards">
      <OverviewCard label="Registered devices" value={data.cards.registered} />
      <OverviewCard label="Fetched (24h)" value={data.cards.fetched24h} hint="Successful HTTP responses" />
      <OverviewCard label="Fetched (7d)" value={data.cards.fetched7d} hint="Successful HTTP responses" />
      <OverviewCard label="Never fetched" value={data.cards.neverFetched} tone={data.cards.neverFetched ? 'attention' : 'default'} />
      <OverviewCard label="No recent fetch" value={data.cards.noRecentFetch} hint={`${data.staleWindowDays}d window; excludes never fetched`} tone={data.cards.noRecentFetch ? 'attention' : 'default'} />
      <OverviewCard label="Pending requests" value={data.cards.pending} tone={data.cards.pending ? 'attention' : 'default'} />
      <OverviewCard label="Config changes (24h)" value={data.cards.configChanges24h} />
    </div>

    <div className="overview-grid overview-grid-primary">
      <section className="surface overview-panel"><div className="overview-panel-heading"><div><span className="eyebrow">DEVICE DELIVERY</span><h2>Config delivery</h2></div><span className="muted">Requested / served versions</span></div><div className="overview-table-wrap"><table className="overview-table"><thead><tr><th>Device</th><th>Current config version</th><th>Last config version served</th><th>Served at (UTC)</th><th>Status</th></tr></thead><tbody>{data.devices.length ? data.devices.map((device) => <tr key={device.deviceId}><td><strong className="mono">{device.deviceId}</strong><small>{device.label} · {device.model}</small></td><td><span className="version-pill">v{device.currentVersion}</span></td><td>{typeof device.lastServedVersion === 'number' ? <span className="version-pill">v{device.lastServedVersion}</span> : '—'}</td><td><span>{formatUtc(device.lastServedAt)}</span>{device.lastServedAt && <small>{formatLocal(device.lastServedAt)}</small>}</td><td><DeviceStatus device={device} /></td></tr>) : <tr><td colSpan={5} className="overview-empty">No registered devices.</td></tr>}</tbody></table></div></section>
      <section className="surface overview-panel"><div className="overview-panel-heading"><div><span className="eyebrow">ATTENTION</span><h2>Needs review</h2></div><TriangleAlert size={17} className="overview-warning-icon" /></div><div className="attention-list">{data.attention.length ? data.attention.slice(0, 12).map((item) => <div className="attention-row" key={`${item.kind}-${item.deviceId}-${item.reason}`}><span className={`attention-marker ${item.kind === 'pending' ? 'attention-marker-pending' : ''}`} /><span><strong className="mono">{item.deviceId}</strong><small>{attentionLabel(item)}{item.reason === 'pending' || item.reason === 'pending-repeated' ? ` · ${item.requestCount || 0} request${item.requestCount === 1 ? '' : 's'}` : ''}</small></span><span className="muted">{formatLocal(item.lastServedAt || item.lastSeenAt)}</span></div>) : <div className="overview-empty">No attention items.</div>}</div></section>
    </div>

    <div className="overview-grid overview-grid-secondary">
      <section className="surface overview-panel"><div className="overview-panel-heading"><div><span className="eyebrow">ADMINISTRATOR ACTIVITY</span><h2>Recent changes</h2></div></div><div className="activity-mini-list">{data.recentAdminActivity.length ? data.recentAdminActivity.slice(0, 8).map((event) => <div className="activity-mini-row" key={event.id}><div><strong>{actionLabel(event)}</strong><small>{event.actor.username || event.actor.type} · {event.resource.id || event.resource.type}</small></div><span>{formatLocal(event.occurredAt)}</span></div>) : <div className="overview-empty">No administrator changes recorded.</div>}</div></section>
      <section className="surface overview-panel"><div className="overview-panel-heading"><div><span className="eyebrow">MODEL DISTRIBUTION</span><h2>Registered profiles</h2></div></div><div className="model-list">{data.modelDistribution.length ? data.modelDistribution.map((entry) => <div className="model-row" key={entry.model}><span className="mono">{entry.model}</span><strong>{entry.count}</strong><span className="model-bar"><i style={{ width: `${Math.max(6, Math.round((entry.count / Math.max(1, data.cards.registered)) * 100))}%` }} /></span></div>) : <div className="overview-empty">No registered devices.</div>}</div></section>
      <section className={`surface overview-panel storage-health ${data.storage.ok ? 'storage-health-ok' : 'storage-health-error'}`}><div className="overview-panel-heading"><div><span className="eyebrow">STORAGE HEALTH</span><h2>{data.storage.ok ? 'Storage healthy' : 'Storage unavailable'}</h2></div><Database size={17} /></div><p>Backend: <strong>{data.storage.backend}</strong></p><p>Read latency: <strong>{data.storage.latencyMs} ms</strong></p>{!data.storage.ok && <p className="storage-health-note">Reads may be incomplete. Retry after the provider recovers.</p>}</section>
    </div>
  </section>;
}
