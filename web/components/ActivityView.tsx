'use client';

import { AlertCircle, ChevronLeft, ChevronRight, Filter, RefreshCw, Search } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ActivityAction, ActivityCategory, ActivityEvent, ActivityResult } from '@/lib/types';

type ActivityResponse = { events: ActivityEvent[]; nextCursor?: string };
type QuickRange = '1h' | '24h' | '7d' | '30d';
type RangeWindow = { from: string; to: string };

function rangeWindow(range: QuickRange): RangeWindow {
  const durations: Record<QuickRange, number> = { '1h': 60 * 60 * 1000, '24h': 24 * 60 * 60 * 1000, '7d': 7 * 24 * 60 * 60 * 1000, '30d': 30 * 24 * 60 * 60 * 1000 };
  const now = Date.now();
  return { from: new Date(now - durations[range]).toISOString(), to: new Date(now).toISOString() };
}

const ACTIONS: ActivityAction[] = [
  'admin.login.succeeded', 'admin.logout', 'device.created', 'device.updated', 'device.deleted',
  'pending-request.dismissed', 'preset.created', 'preset.updated', 'preset.deleted',
  'config.request.succeeded', 'config.request.unknown-device', 'config.request.invalid-device-id',
  'config.request.failed', 'storage.unavailable', 'configuration.validation.failed', 'activity-log.write.failed'
];
const RESULTS: ActivityResult[] = ['succeeded', 'served', 'not-found', 'invalid', 'failed'];

function utc(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : `${date.toISOString().slice(0, 19).replace('T', ' ')} UTC`;
}

function local(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function title(value: string) {
  return value.replaceAll('.', ' ');
}

function EventDetails({ event }: { event: ActivityEvent }) {
  const versions = event.configVersions;
  const change = event.change;
  return <details className="activity-details"><summary>Details</summary><div className="activity-detail-grid"><span><small>UTC</small><strong>{utc(event.occurredAt)}</strong></span><span><small>Browser local</small><strong>{local(event.occurredAt)}</strong></span><span><small>Actor</small><strong>{event.actor.username || event.actor.type}</strong></span><span><small>Resource</small><strong>{event.resource.id || event.resource.type}</strong></span>{event.resource.model && <span><small>Model</small><strong>{event.resource.model}</strong></span>}<span><small>Result</small><strong>{event.result}</strong></span>{versions?.previous !== undefined && <span><small>Previous version</small><strong>v{versions.previous}</strong></span>}{versions?.current !== undefined && <span><small>Current version</small><strong>v{versions.current}</strong></span>}{versions?.served !== undefined && <span><small>Served version</small><strong>v{versions.served}</strong></span>}{change && <span><small>Safe counts</small><strong>{[change.connectionsBefore !== undefined && `connections ${change.connectionsBefore}→${change.connectionsAfter ?? 0}`, change.channelsBefore !== undefined && `channels ${change.channelsBefore}→${change.channelsAfter ?? 0}`].filter(Boolean).join(' · ') || 'Section changes recorded'}</strong></span>}</div></details>;
}

export function ActivityView() {
  const [range, setRange] = useState<QuickRange>('24h');
  const [frozenRange, setFrozenRange] = useState<RangeWindow>(() => rangeWindow('24h'));
  const [deviceId, setDeviceId] = useState('');
  const [administrator, setAdministrator] = useState('');
  const [category, setCategory] = useState<ActivityCategory | ''>('');
  const [action, setAction] = useState<ActivityAction | ''>('');
  const [result, setResult] = useState<ActivityResult | ''>('');
  const [model, setModel] = useState('');
  const [version, setVersion] = useState('');
  const [events, setEvents] = useState<ActivityEvent[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>();
  const [cursorHistory, setCursorHistory] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const filterKey = useMemo(() => JSON.stringify({ range, deviceId, administrator, category, action, result, model, version }), [range, deviceId, administrator, category, action, result, model, version]);
  const buildUrl = useCallback((cursor?: string, bounds = frozenRange) => {
    const query = new URLSearchParams({ from: bounds.from, to: bounds.to, limit: '50' });
    if (deviceId.trim()) query.set('deviceId', deviceId.trim().toUpperCase());
    if (administrator.trim()) query.set('administrator', administrator.trim());
    if (category) query.set('category', category);
    if (action) query.set('action', action);
    if (result) query.set('result', result);
    if (model.trim()) query.set('model', model.trim());
    if (version.trim()) query.set('configVersion', version.trim());
    if (cursor) query.set('cursor', cursor);
    return `/api/activity?${query.toString()}`;
  }, [frozenRange, deviceId, administrator, category, action, result, model, version]);

  const load = useCallback(async (cursor?: string, bounds = frozenRange) => {
    setLoading(true); setError('');
    try {
      const response = await fetch(buildUrl(cursor, bounds), { cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(typeof body?.error === 'string' ? body.error : 'Could not load activity');
      const page = body as ActivityResponse;
      setEvents(page.events || []); setNextCursor(page.nextCursor);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load activity');
      setEvents([]); setNextCursor(undefined);
    } finally { setLoading(false); }
  }, [buildUrl, frozenRange]);

  useEffect(() => {
    const nextWindow = rangeWindow(range);
    setFrozenRange(nextWindow);
    setCursorHistory([]);
    void load(undefined, nextWindow);
  // The filter key intentionally resets the server cursor and freezes a new exact range.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  function nextPage() {
    if (!nextCursor) return;
    setCursorHistory((history) => [...history, nextCursor]);
    void load(nextCursor);
  }
  function previousPage() {
    if (!cursorHistory.length) return;
    const history = cursorHistory.slice(0, -1);
    setCursorHistory(history);
    void load(history.length ? history[history.length - 1] : undefined);
  }

  return <section className="activity-view surface"><div className="activity-heading"><div><span className="eyebrow">AUDIT TRAIL</span><h2>Activity</h2><p>Safe operational events, newest first. No tokens, passwords, or raw configuration are shown.</p></div><button className="icon-button" type="button" onClick={() => void load(cursorHistory[cursorHistory.length - 1])} disabled={loading} aria-label="Refresh activity" title="Refresh activity"><RefreshCw size={17} className={loading ? 'spin' : ''} /></button></div><div className="activity-filters"><div className="quick-range" role="group" aria-label="Activity time range">{(['1h', '24h', '7d', '30d'] as QuickRange[]).map((option) => <button type="button" key={option} className={range === option ? 'quick-range-active' : ''} onClick={() => setRange(option)}>{option}</button>)}</div><label className="activity-filter-field"><Search size={15} /><span className="sr-only">Device ID</span><input value={deviceId} onChange={(event) => setDeviceId(event.target.value)} placeholder="Device ID" /></label><label className="activity-filter-field"><span className="sr-only">Administrator</span><input value={administrator} onChange={(event) => setAdministrator(event.target.value)} placeholder="Administrator" /></label><label className="activity-filter-field"><span className="sr-only">Model</span><input value={model} onChange={(event) => setModel(event.target.value)} placeholder="Model" /></label><label className="activity-filter-field"><span className="sr-only">Version</span><input inputMode="numeric" value={version} onChange={(event) => setVersion(event.target.value)} placeholder="Version" /></label><label className="activity-filter-field"><span className="sr-only">Category</span><select value={category} onChange={(event) => setCategory(event.target.value as ActivityCategory | '')}><option value="">All categories</option><option value="administrator">Administrator</option><option value="device-configuration">Device configuration</option><option value="system">System</option></select></label><label className="activity-filter-field"><span className="sr-only">Action</span><select value={action} onChange={(event) => setAction(event.target.value as ActivityAction | '')}><option value="">All actions</option>{ACTIONS.map((option) => <option key={option} value={option}>{title(option)}</option>)}</select></label><label className="activity-filter-field"><span className="sr-only">Result</span><select value={result} onChange={(event) => setResult(event.target.value as ActivityResult | '')}><option value="">All results</option>{RESULTS.map((option) => <option key={option} value={option}>{option}</option>)}</select></label><span className="activity-filter-mark"><Filter size={15} /> Server-side filters</span></div>{error && <div className="form-error activity-error"><AlertCircle size={15} />{error}<button className="quiet-button" type="button" onClick={() => void load()}>Retry</button></div>}<div className="activity-table-wrap"><table className="activity-table"><thead><tr><th>When</th><th>Action</th><th>Actor</th><th>Resource</th><th>Result</th><th /></tr></thead><tbody>{loading && !events.length ? <tr><td colSpan={6} className="activity-state"><RefreshCw size={17} className="spin" />Loading activity...</td></tr> : events.length ? events.map((event) => <tr key={event.id}><td><strong>{utc(event.occurredAt)}</strong><small>{local(event.occurredAt)}</small></td><td><strong>{title(event.action)}</strong><small>{event.category}</small></td><td>{event.actor.username || event.actor.type}</td><td><span className="mono">{event.resource.id || event.resource.type}</span>{event.resource.label && <small>{event.resource.label}</small>}</td><td><span className={`activity-result activity-result-${event.result}`}>{event.result}</span></td><td><EventDetails event={event} /></td></tr>) : <tr><td colSpan={6} className="activity-state">No events match these filters.</td></tr>}</tbody></table></div><div className="activity-pagination"><button className="quiet-button" type="button" onClick={previousPage} disabled={!cursorHistory.length || loading}><ChevronLeft size={15} /> Newer</button><span>{events.length ? `${events.length} event${events.length === 1 ? '' : 's'}` : 'No events'}</span><button className="quiet-button" type="button" onClick={nextPage} disabled={!nextCursor || loading}>Older <ChevronRight size={15} /></button></div></section>;
}
