import { describe, expect, it } from 'vitest';
import { BOT_ID_PROTECTED_ROUTES } from './botid-routes';

function isProtected(pathname: string, method: string) {
  return BOT_ID_PROTECTED_ROUTES.some((route) => {
    const pattern = new RegExp(`^${route.path
      .replace(/[.?+^$[\]\\(){}|-]/g, '\\$&')
      .replaceAll('*', '.*')}$`);
    return route.method === method && pattern.test(pathname);
  });
}

describe('BotID client route protection', () => {
  it('protects Save Configuration for dynamic device IDs', () => {
    expect(isProtected('/api/devices/ABC123', 'PATCH')).toBe(true);
  });

  it('protects logout as an authenticated mutation', () => {
    expect(isProtected('/api/logout', 'POST')).toBe(true);
    expect(BOT_ID_PROTECTED_ROUTES).toContainEqual({ path: '/api/logout', method: 'POST' });
  });

  it('protects delete without using Next.js parameter syntax', () => {
    expect(isProtected('/api/devices/ABC123', 'DELETE')).toBe(true);
    expect(isProtected('/api/devices/pending/ABC123', 'DELETE')).toBe(true);
    expect(BOT_ID_PROTECTED_ROUTES).toContainEqual({ path: '/api/devices/pending/*', method: 'DELETE' });
    expect(BOT_ID_PROTECTED_ROUTES.some((route) => route.path.includes(':'))).toBe(false);
  });

  it('does not add BotID headers to read-only device requests', () => {
    expect(isProtected('/api/devices/ABC123', 'GET')).toBe(false);
  });

  it('protects preset mutations and import apply while leaving preview read-only', () => {
    expect(isProtected('/api/config-presets', 'POST')).toBe(true);
    expect(isProtected('/api/config-presets/ops-preset', 'PATCH')).toBe(true);
    expect(isProtected('/api/config-presets/ops-preset', 'DELETE')).toBe(true);
    expect(isProtected('/api/config-presets/ops-preset/duplicate', 'POST')).toBe(true);
    expect(isProtected('/api/devices/ABC123/config-import/apply', 'POST')).toBe(true);
    expect(isProtected('/api/devices/ABC123/config-import/preview', 'POST')).toBe(false);
  });
});
