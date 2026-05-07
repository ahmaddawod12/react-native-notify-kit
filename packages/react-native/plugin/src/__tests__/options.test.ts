import {
  DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
  DEFAULT_IOS_NSE_TARGET_NAME,
  normalizeIosNotificationServiceExtensionOptions,
  normalizeNotifyKitPluginOptions,
} from '../options';

describe('NotifyKit Expo plugin option normalization', () => {
  it('normalizes undefined as disabled', () => {
    expect(normalizeNotifyKitPluginOptions()).toEqual({
      ios: {
        notificationServiceExtension: {
          enabled: false,
          targetName: DEFAULT_IOS_NSE_TARGET_NAME,
          bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
        },
      },
    });
  });

  it('normalizes false as disabled', () => {
    expect(normalizeIosNotificationServiceExtensionOptions(false)).toMatchObject({
      enabled: false,
      targetName: DEFAULT_IOS_NSE_TARGET_NAME,
      bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
    });
  });

  it('normalizes true alias as enabled with defaults', () => {
    expect(normalizeIosNotificationServiceExtensionOptions(true)).toEqual({
      enabled: true,
      targetName: DEFAULT_IOS_NSE_TARGET_NAME,
      bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
    });
  });

  it('normalizes object config with defaults', () => {
    expect(
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
      }),
    ).toEqual({
      enabled: true,
      targetName: DEFAULT_IOS_NSE_TARGET_NAME,
      bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
    });
  });

  it('normalizes object config with custom targetName and bundleSuffix', () => {
    expect(
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
        targetName: 'Custom.NotifyKit_NSE-1',
        bundleSuffix: '.Custom-NSE.1',
      }),
    ).toEqual({
      enabled: true,
      targetName: 'Custom.NotifyKit_NSE-1',
      bundleSuffix: '.Custom-NSE.1',
    });
  });

  it('normalizes { enabled: false } as disabled', () => {
    expect(
      normalizeIosNotificationServiceExtensionOptions({
        enabled: false,
      }),
    ).toMatchObject({
      enabled: false,
      targetName: DEFAULT_IOS_NSE_TARGET_NAME,
      bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
    });
  });

  it('rejects empty targetName', () => {
    expect(() =>
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
        targetName: '',
      }),
    ).toThrow(/targetName must be a non-empty string/);
  });

  it('rejects targetName with unsafe characters', () => {
    expect(() =>
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
        targetName: "Foo'; system('rm -rf /'); #",
      }),
    ).toThrow(/Invalid notification service extension targetName/);
  });

  it('rejects bundleSuffix without leading dot', () => {
    expect(() =>
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
        bundleSuffix: 'NotifyKitNSE',
      }),
    ).toThrow(/Invalid notification service extension bundleSuffix/);
  });

  it('rejects bundleSuffix with unsafe characters', () => {
    expect(() =>
      normalizeIosNotificationServiceExtensionOptions({
        enabled: true,
        bundleSuffix: '".evil"',
      }),
    ).toThrow(/Invalid notification service extension bundleSuffix/);
  });
});
