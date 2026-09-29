import { deliverDeviceConfig } from '@/lib/device-config-delivery';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

/** Device-ID lookup endpoint used by managed Android clients. */
export async function GET(_request: Request, context: Context) {
  const { deviceId } = await context.params;
  return deliverDeviceConfig(deviceId);
}
