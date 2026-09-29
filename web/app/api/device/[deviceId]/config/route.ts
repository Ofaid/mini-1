import { deliverDeviceConfig } from '@/lib/device-config-delivery';

export const runtime = 'nodejs';

type Context = { params: Promise<{ deviceId: string }> };

export async function GET(_request: Request, context: Context) {
  const { deviceId } = await context.params;
  return deliverDeviceConfig(deviceId);
}
