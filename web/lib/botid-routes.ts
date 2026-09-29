export const BOT_ID_PROTECTED_ROUTES = [
  { path: '/api/setup', method: 'POST' },
  { path: '/api/login', method: 'POST' },
  { path: '/api/logout', method: 'POST' },
  { path: '/api/devices', method: 'POST' },
  { path: '/api/devices/pending/*', method: 'DELETE' },
  { path: '/api/devices/*', method: 'PATCH' },
  { path: '/api/devices/*', method: 'DELETE' },
  { path: '/api/config-presets', method: 'POST' },
  { path: '/api/config-presets/*', method: 'PATCH' },
  { path: '/api/config-presets/*', method: 'DELETE' },
  { path: '/api/config-presets/*/duplicate', method: 'POST' },
  { path: '/api/devices/*/config-import/apply', method: 'POST' }
] as const;
