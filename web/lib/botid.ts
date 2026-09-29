export async function requireHumanMutation() {
  if (process.env.BOTID_ENFORCE !== 'true') return true;
  try {
    const { checkBotId } = await import('botid/server');
    const result = await checkBotId({
      developmentOptions: { isDevelopment: process.env.NODE_ENV !== 'production' }
    });
    return result.isHuman === true && result.isBot !== true;
  } catch {
    return false;
  }
}
