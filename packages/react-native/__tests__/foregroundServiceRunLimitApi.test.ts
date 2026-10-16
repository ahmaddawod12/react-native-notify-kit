import type { Notification } from '../src';

const mockDisplay = jest.fn(async (_notification: Notification) => undefined);
jest.mock('react-native', () => ({
  AppRegistry: { registerHeadlessTask: jest.fn() },
  AppState: { currentState: 'active' },
  Image: { resolveAssetSource: jest.fn() },
  NativeEventEmitter: jest.fn(() => ({ addListener: jest.fn() })),
  Platform: { OS: 'android', Version: 35 },
  TurboModuleRegistry: {
    getEnforcing: () => ({
      displayNotification: mockDisplay,
      addListener: jest.fn(),
      removeListeners: jest.fn(),
    }),
  },
}));
import notifee from '../src';

beforeEach(() => mockDisplay.mockClear());
test('real public displayNotification serializes opt-in into native payload', async () => {
  const id = await notifee.displayNotification({
    id: 'public-run',
    android: {
      channelId: 'sync',
      asForegroundService: true,
      foregroundServiceTypes: [1],
      foregroundServiceBestEffortStopAfterMs: 60000,
    },
  });
  expect(id).toBe('public-run');
  expect(mockDisplay).toHaveBeenCalledTimes(1);
  expect(mockDisplay.mock.calls[0][0].android?.foregroundServiceBestEffortStopAfterMs).toBe(60000);
});
test('public rejection happens before invoking native', async () => {
  expect(() =>
    notifee.displayNotification({
      android: { channelId: 'sync', foregroundServiceBestEffortStopAfterMs: 60000 },
    }),
  ).toThrow('foregroundServiceBestEffortStopAfterMs');
  expect(mockDisplay).not.toHaveBeenCalled();
});
test('ordinary public notification has no run-limit property', async () => {
  await notifee.displayNotification({
    id: 'ordinary',
    android: { channelId: 'sync', asForegroundService: true },
  });
  expect(mockDisplay.mock.calls[0][0].android).not.toHaveProperty(
    'foregroundServiceBestEffortStopAfterMs',
  );
});
