import { existsSync } from 'node:fs';
import path from 'node:path';
import type { ConfigContext, ExpoConfig } from 'expo/config';

type ExpoPlugin = NonNullable<ExpoConfig['plugins']>[number];

const FCM_ENV = 'EXPO_PUBLIC_NOTIFYKIT_EXPO_SMOKE_FCM';
const GOOGLE_SERVICES_FILE = './firebase/GoogleService-Info.plist';
const isFcmModeEnabled = process.env[FCM_ENV] === '1';

const requireGoogleServicesFile = (): void => {
  const googleServicesFilePath = path.join(__dirname, GOOGLE_SERVICES_FILE);

  if (!existsSync(googleServicesFilePath)) {
    throw new Error(
      `apps/expo-smoke FCM mode requires ${GOOGLE_SERVICES_FILE}. ` +
        `Place the local iOS Firebase plist there or unset ${FCM_ENV}.`,
    );
  }
};

const getFcmPlugins = (): ExpoPlugin[] => {
  if (!isFcmModeEnabled) {
    return [];
  }

  requireGoogleServicesFile();

  return [
    '@react-native-firebase/app',
    '@react-native-firebase/messaging',
    [
      'expo-build-properties',
      {
        ios: {
          useFrameworks: 'static',
        },
      },
    ],
    './plugins/withFirebaseAppDelegateExpo55',
  ];
};

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: 'NotifyKit Expo Smoke',
  slug: 'notify-kit-expo-smoke',
  version: '1.0.0',
  orientation: 'portrait',
  scheme: 'notifykitexposmoke',
  ios: {
    ...config.ios,
    bundleIdentifier: 'com.notifykit.exposmoke',
    supportsTablet: true,
    ...(isFcmModeEnabled
      ? {
          googleServicesFile: GOOGLE_SERVICES_FILE,
          entitlements: {
            ...(config.ios?.entitlements ?? {}),
            'aps-environment': 'development',
          },
        }
      : {}),
  },
  android: {
    ...config.android,
    package: 'com.notifykit.exposmoke',
  },
  plugins: [
    ...(config.plugins ?? []),
    ...getFcmPlugins(),
    [
      'react-native-notify-kit',
      {
        ios: {
          notificationServiceExtension: {
            enabled: true,
            targetName: 'NotifyKitNSE',
            bundleSuffix: '.NotifyKitNSE',
          },
        },
      },
    ],
  ],
});
