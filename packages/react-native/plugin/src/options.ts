export const DEFAULT_IOS_NSE_TARGET_NAME = 'NotifyKitNSE';
export const DEFAULT_IOS_NSE_BUNDLE_SUFFIX = '.NotifyKitNSE';

const TARGET_NAME_PATTERN = /^[A-Za-z0-9_\-.]+$/;
const BUNDLE_SUFFIX_PATTERN = /^\.[A-Za-z0-9\-.]+$/;

export interface NotifyKitPluginOptions {
  ios?: {
    notificationServiceExtension?: IosNotificationServiceExtensionInput;
  };
}

export type IosNotificationServiceExtensionInput =
  | boolean
  | {
      enabled?: boolean;
      targetName?: string;
      bundleSuffix?: string;
    };

export interface NormalizedNotifyKitPluginOptions {
  ios: {
    notificationServiceExtension: NormalizedIosNotificationServiceExtensionOptions;
  };
}

export interface NormalizedIosNotificationServiceExtensionOptions {
  enabled: boolean;
  targetName: string;
  bundleSuffix: string;
}

export function normalizeNotifyKitPluginOptions(
  options: NotifyKitPluginOptions = {},
): NormalizedNotifyKitPluginOptions {
  return {
    ios: {
      notificationServiceExtension: normalizeIosNotificationServiceExtensionOptions(
        options.ios?.notificationServiceExtension,
      ),
    },
  };
}

export function normalizeIosNotificationServiceExtensionOptions(
  input?: IosNotificationServiceExtensionInput,
): NormalizedIosNotificationServiceExtensionOptions {
  if (input === undefined || input === false) {
    return disabledIosNotificationServiceExtensionOptions();
  }

  if (input === true) {
    return validateEnabledIosNotificationServiceExtensionOptions({
      enabled: true,
      targetName: DEFAULT_IOS_NSE_TARGET_NAME,
      bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
    });
  }

  if (!isPlainObject(input)) {
    throw new Error(
      '[react-native-notify-kit] ios.notificationServiceExtension must be a boolean or an object.',
    );
  }

  if (input.enabled !== undefined && typeof input.enabled !== 'boolean') {
    throw new Error(
      '[react-native-notify-kit] ios.notificationServiceExtension.enabled must be a boolean.',
    );
  }

  if (input.enabled !== true) {
    return disabledIosNotificationServiceExtensionOptions();
  }

  return validateEnabledIosNotificationServiceExtensionOptions({
    enabled: true,
    targetName: input.targetName ?? DEFAULT_IOS_NSE_TARGET_NAME,
    bundleSuffix: input.bundleSuffix ?? DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
  });
}

function disabledIosNotificationServiceExtensionOptions(): NormalizedIosNotificationServiceExtensionOptions {
  return {
    enabled: false,
    targetName: DEFAULT_IOS_NSE_TARGET_NAME,
    bundleSuffix: DEFAULT_IOS_NSE_BUNDLE_SUFFIX,
  };
}

function validateEnabledIosNotificationServiceExtensionOptions(
  options: NormalizedIosNotificationServiceExtensionOptions,
): NormalizedIosNotificationServiceExtensionOptions {
  if (typeof options.targetName !== 'string' || options.targetName.length === 0) {
    throw new Error(
      '[react-native-notify-kit] ios.notificationServiceExtension.targetName must be a non-empty string.',
    );
  }

  if (!TARGET_NAME_PATTERN.test(options.targetName)) {
    throw new Error(
      `[react-native-notify-kit] Invalid notification service extension targetName '${options.targetName}'. ` +
        'Use only letters, digits, underscores, hyphens, and dots.',
    );
  }

  if (typeof options.bundleSuffix !== 'string' || options.bundleSuffix.length === 0) {
    throw new Error(
      '[react-native-notify-kit] ios.notificationServiceExtension.bundleSuffix must be a non-empty string.',
    );
  }

  if (!BUNDLE_SUFFIX_PATTERN.test(options.bundleSuffix)) {
    throw new Error(
      `[react-native-notify-kit] Invalid notification service extension bundleSuffix '${options.bundleSuffix}'. ` +
        "It must start with '.' and contain only letters, digits, hyphens, and dots.",
    );
  }

  return options;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
