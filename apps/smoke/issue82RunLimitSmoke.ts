import notifee, { AndroidForegroundServiceType, Event } from 'react-native-notify-kit';

const id = 'issue82-public-run-limit';
const scenario = 'issue82-public-run-limit';

export async function displayRunLimitSmoke(enabled: boolean, update = false): Promise<void> {
  const channelId = await notifee.createChannel({ id: scenario, name: 'FGS run limit smoke' });
  await notifee.displayNotification({
    id,
    title: update ? 'Public run limit updated' : 'Public run limit smoke',
    body: enabled ? 'Best-effort voluntary duration: 60 seconds' : 'No voluntary limit requested',
    data: { smokeScenario: scenario },
    android: {
      channelId,
      asForegroundService: true,
      foregroundServiceTypes: [AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_DATA_SYNC],
      ...(enabled && !update ? { foregroundServiceBestEffortStopAfterMs: 60000 } : {}),
    },
  });
  console.log('RUN_LIMIT_PUBLIC_DISPLAY_RETURNED', JSON.stringify({ id, enabled, update }));
}

export function recordRunLimitSmokeEvent(event: Event): boolean {
  if (event.detail.notification?.data?.smokeScenario !== scenario) return false;
  console.log('RUN_LIMIT_PUBLIC_EVENT', JSON.stringify(event));
  return true;
}

export function isRunLimitSmokeRunner(notification: {
  id?: string;
  data?: Record<string, unknown>;
}): boolean {
  return notification.id === id && notification.data?.smokeScenario === scenario;
}
