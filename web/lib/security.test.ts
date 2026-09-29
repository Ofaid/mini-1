import { describe, expect, it } from 'vitest';
import { createSession, hashSecret, readSession, securityHeaders, verifySecret } from './security';

describe('portal security primitives', () => {
  it('hashes passwords and verifies only the original value', () => {
    const encoded = hashSecret('correct horse battery staple');
    expect(verifySecret('correct horse battery staple', encoded)).toBe(true);
    expect(verifySecret('wrong password', encoded)).toBe(false);
  });

  it('signs and reads an expiring admin session', () => {
    expect(readSession(createSession('operator'))?.username).toBe('operator');
    expect(readSession('not-a-session')).toBeNull();
  });

  it('publishes no-store and browser isolation headers', () => {
    const headers = securityHeaders();
    expect(headers['Cache-Control']).toContain('no-store');
    expect(headers['X-Frame-Options']).toBe('DENY');
    expect(headers['X-Content-Type-Options']).toBe('nosniff');
  });
});
