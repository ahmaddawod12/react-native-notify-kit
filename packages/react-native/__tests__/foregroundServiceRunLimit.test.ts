import validateAndroidNotification from '../src/validators/validateAndroidNotification';
import { Platform } from 'react-native';

jest.mock('react-native', () => ({
  Image: { resolveAssetSource: jest.fn() },
  Platform: { OS: 'android', Version: 35 },
}));

const key = 'foregroundServiceBestEffortStopAfterMs';
const config = (value: unknown) => ({
  channelId: 'sync',
  asForegroundService: true,
  foregroundServiceTypes: [1],
  [key]: value,
});

describe('public best-effort foreground run limit', () => {
  beforeEach(() => {
    Object.assign(Platform, { OS: 'android', Version: 35 });
  });
  test('absent and undefined remain disabled', () => {
    expect(
      validateAndroidNotification({ channelId: 'sync', asForegroundService: true }),
    ).not.toHaveProperty(key);
    expect(validateAndroidNotification(config(undefined))).not.toHaveProperty(key);
  });
  test.each([60000, 18000000])('preserves valid boundary %s for serialization', value => {
    expect(validateAndroidNotification(config(value))).toHaveProperty(key, value);
  });
  test.each([
    null,
    '60000',
    true,
    NaN,
    Infinity,
    -Infinity,
    0,
    -1,
    60000.5,
    59999,
    18000001,
    Number.MAX_SAFE_INTEGER + 1,
  ])('rejects invalid %s with property path', value => {
    expect(() => validateAndroidNotification(config(value))).toThrow(key);
  });
  test('requires explicit foreground service', () => {
    expect(() =>
      validateAndroidNotification({ ...config(60000), asForegroundService: false }),
    ).toThrow(key);
  });
  test('rejects incompatible explicit type', () => {
    expect(() =>
      validateAndroidNotification({ ...config(60000), foregroundServiceTypes: [2] }),
    ).toThrow(key);
    expect(() =>
      validateAndroidNotification({ ...config(60000), foregroundServiceTypes: [1, 2] }),
    ).toThrow(key);
  });
  test('rejects unsupported Android before bridge admission', () => {
    Object.assign(Platform, { Version: 34 });
    expect(() => validateAndroidNotification(config(60000))).toThrow(key);
  });
});
