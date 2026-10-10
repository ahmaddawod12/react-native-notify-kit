import type { Event } from 'react-native-notify-kit';

const mockNativeModule = { addListener: jest.fn(), removeListeners: jest.fn() };
const mockGetEnforcing = jest.fn();
const mockNativeEventEmitter = jest.fn();
const mockRegisterHeadlessTask = jest.fn();

jest.mock('react-native', () => ({
  AppRegistry: { registerHeadlessTask: mockRegisterHeadlessTask },
  AppState: { currentState: 'active' },
  NativeEventEmitter: mockNativeEventEmitter,
  Platform: { OS: 'android', Version: 35 },
  TurboModuleRegistry: { getEnforcing: mockGetEnforcing },
}));

const eventName = 'app.notifee.notification-event';
const legacyTypes = {
  UNKNOWN: -1,
  DISMISSED: 0,
  PRESS: 1,
  ACTION_PRESS: 2,
  DELIVERED: 3,
  APP_BLOCKED: 4,
  CHANNEL_BLOCKED: 5,
  CHANNEL_GROUP_BLOCKED: 6,
  TRIGGER_NOTIFICATION_CREATED: 7,
  FG_ALREADY_EXIST: 8,
};
const eventCases = [
  { name: 'timeout with explicit type', type: 9, metadata: { startId: 42, fgsType: 1 } },
  { name: 'timeout with sentinel type', type: 9, metadata: { startId: 43, fgsType: -1 } },
  { name: 'timeout without metadata', type: 9, metadata: {} },
  ...Object.entries(legacyTypes).map(([name, type]) => ({ name, type, metadata: {} })),
];

describe('public Android foreground service timeout event', () => {
  let publicApi: typeof import('react-native-notify-kit');
  let nativeListeners: Map<string, (payload: unknown) => void>;
  let headlessTasks: Map<string, () => (payload: unknown) => Promise<void>>;

  beforeEach(() => {
    jest.resetModules();
    jest.clearAllMocks();
    nativeListeners = new Map();
    headlessTasks = new Map();
    mockGetEnforcing.mockReturnValue(mockNativeModule);
    mockNativeEventEmitter.mockImplementation(() => ({
      addListener: (name: string, listener: (payload: unknown) => void) => {
        nativeListeners.set(name, listener);
        return { remove: jest.fn() };
      },
    }));
    mockRegisterHeadlessTask.mockImplementation((name, factory) => {
      headlessTasks.set(name, factory);
    });
    // Resolve the real package entry point, enum, API, and JS emitter. Only RN is mocked.
    publicApi = require('react-native-notify-kit');
  });

  test('exports FG_TIMEOUT = 9 and preserves all existing EventType values', () => {
    expect(publicApi.EventType.FG_TIMEOUT).toBe(9);
    for (const [name, value] of Object.entries({ ...legacyTypes, FG_TIMEOUT: 9 })) {
      expect(publicApi.EventType[name as keyof typeof publicApi.EventType]).toBe(value);
      expect(publicApi.EventType[value]).toBe(name);
    }
  });

  test.each(eventCases)('foreground relay preserves $name', ({ type, metadata }) => {
    const observer = jest.fn<void, [Event]>();
    const detail = Object.freeze({
      notification: Object.freeze({
        id: 'foreground-event',
        data: Object.freeze({ origin: 'native' }),
      }),
      ...metadata,
    });
    const payload = Object.freeze({ type, detail, headless: false });
    const unsubscribe = publicApi.default.onForegroundEvent(observer);
    try {
      const listener = nativeListeners.get(eventName);
      expect(listener).toEqual(expect.any(Function));
      listener!(payload);

      expect(observer).toHaveBeenCalledTimes(1);
      expect(observer).toHaveBeenCalledWith({ type, detail });
      if (type === 9) {
        expect(observer.mock.calls[0][0].type).toBe(publicApi.EventType.FG_TIMEOUT);
        expect(observer.mock.calls[0][0].type).not.toBe(publicApi.EventType.UNKNOWN);
      }
      if (!('startId' in metadata)) {
        expect(observer.mock.calls[0][0].detail).not.toHaveProperty('startId');
        expect(observer.mock.calls[0][0].detail).not.toHaveProperty('fgsType');
      }
    } finally {
      unsubscribe();
    }
  });

  test.each(eventCases)('Android headless handler preserves $name', async ({ type, metadata }) => {
    const observer = jest.fn<Promise<void>, [Event]>(async () => undefined);
    const detail = Object.freeze({
      notification: Object.freeze({
        id: 'background-event',
        data: Object.freeze({ origin: 'native' }),
      }),
      ...metadata,
    });
    const payload = Object.freeze({ type, detail, headless: true });
    publicApi.default.onBackgroundEvent(observer);
    const factory = headlessTasks.get(eventName);
    expect(factory).toEqual(expect.any(Function));
    await factory!()(payload);

    expect(observer).toHaveBeenCalledTimes(1);
    expect(observer).toHaveBeenCalledWith(payload);
    if (type === 9) {
      expect(observer.mock.calls[0][0].type).toBe(publicApi.EventType.FG_TIMEOUT);
      expect(observer.mock.calls[0][0].type).not.toBe(publicApi.EventType.UNKNOWN);
    }
    if (!('startId' in metadata)) {
      expect(observer.mock.calls[0][0].detail).not.toHaveProperty('startId');
      expect(observer.mock.calls[0][0].detail).not.toHaveProperty('fgsType');
    }
    expect(mockGetEnforcing).not.toHaveBeenCalled();
    expect(mockNativeEventEmitter).not.toHaveBeenCalled();
  });
});
