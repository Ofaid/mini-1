import { NextResponse } from 'next/server';
import { getAdmin } from './storage';
import { requireHumanMutation } from './botid';
import { readSession, sameOrigin, securityHeaders, sessionCookieName } from './security';

export function jsonResponse<T>(body: T, status = 200, headers?: HeadersInit) {
  const response = NextResponse.json(body, { status });
  for (const [key, value] of Object.entries(securityHeaders())) response.headers.set(key, value);
  if (headers) {
    const extra = new Headers(headers);
    extra.forEach((value, key) => response.headers.set(key, value));
  }
  return response;
}

export function errorResponse(message: string, status = 400, headers?: HeadersInit) {
  return jsonResponse({ error: message }, status, headers);
}

export async function readJson(request: Request) {
  try {
    return await request.json() as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function sessionFromRequest(request: Request) {
  return readSession(request.headers.get('cookie')?.split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${sessionCookieName()}=`))
    ?.slice(sessionCookieName().length + 1));
}

export async function requireAdmin(request: Request) {
  const session = sessionFromRequest(request);
  if (!session) return { response: errorResponse('Authentication required', 401) } as const;
  const admin = await getAdmin();
  if (!admin || admin.username !== session.username) {
    return { response: errorResponse('Authentication required', 401) } as const;
  }
  return { username: session.username } as const;
}

export async function requireAdminMutation(request: Request) {
  const admin = await requireAdmin(request);
  if ('response' in admin) return admin;
  if (!sameOrigin(request)) return { response: errorResponse('Invalid request origin', 403) } as const;
  if (!(await requireHumanMutation())) return { response: errorResponse('Browser verification required', 403) } as const;
  return admin;
}
