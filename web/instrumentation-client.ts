import { initBotId } from 'botid/client/core';
import { BOT_ID_PROTECTED_ROUTES } from './lib/botid-routes';

// BotID uses `*` wildcards, not Next.js `:parameter` route syntax. Initializing here makes the
// fetch/XHR interceptor available before the admin UI can submit a protected mutation.
initBotId({ protect: [...BOT_ID_PROTECTED_ROUTES] });
