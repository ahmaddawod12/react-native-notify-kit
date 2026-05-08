import { registerRootComponent } from 'expo';
import { Platform } from 'react-native';
import type { FirebaseMessagingTypes } from '@react-native-firebase/messaging';
import notifee, { EventType } from 'react-native-notify-kit';

import App from './App';

const CHANNEL_ID = 'expo-smoke-default';
const isFcmModeEnabled =
  process.env.EXPO_PUBLIC_NOTIFYKIT_EXPO_SMOKE_FCM === '1' && Platform.OS === 'ios';

type MessagingModule = typeof import('@react-native-firebase/messaging');
type NotifyKitFcmMessage = Parameters<typeof notifee.handleFcmMessage>[0];

const getErrorMessage = (error: unknown): string => {
  if (error instanceof Error) {
    return error.message;
  }

  return String(error);
};

const trimMarkerDetail = (value: string): string => value.replace(/\s+/g, ' ').trim().slice(0, 160);

const getMessaging = (): FirebaseMessagingTypes.Module => {
  require('@react-native-firebase/app');
  const messagingModule = require('@react-native-firebase/messaging') as MessagingModule;
  return messagingModule.default();
};

const getMessageMarkerDetail = (remoteMessage: FirebaseMessagingTypes.RemoteMessage): string =>
  remoteMessage.messageId ?? remoteMessage.from ?? 'unknown';

const configureFcmMode = (): void => {
  if (!isFcmModeEnabled) {
    return;
  }

  try {
    void notifee
      .setFcmConfig({
        defaultChannelId: CHANNEL_ID,
        defaultPressAction: {
          id: 'default',
        },
        fallbackBehavior: 'display',
      })
      .catch(error => {
        console.log(`SMOKE:FCM_ERROR config ${trimMarkerDetail(getErrorMessage(error))}`);
      });

    const messaging = getMessaging();

    messaging.setBackgroundMessageHandler(async remoteMessage => {
      console.log(`SMOKE:FCM_BACKGROUND_MESSAGE ${getMessageMarkerDetail(remoteMessage)}`);

      try {
        const result = await notifee.handleFcmMessage(remoteMessage as NotifyKitFcmMessage);
        console.log(`SMOKE:FCM_HANDLE_OK ${result ?? 'null'}`);
        return result;
      } catch (error) {
        console.log(`SMOKE:FCM_ERROR background ${trimMarkerDetail(getErrorMessage(error))}`);
        return undefined;
      }
    });

    notifee.onBackgroundEvent(async ({ type, detail }) => {
      if (type === EventType.PRESS || type === EventType.ACTION_PRESS) {
        console.log(
          `SMOKE:BACKGROUND_EVENT_PRESS ${
            detail.pressAction?.id ?? detail.notification?.id ?? 'unknown'
          }`,
        );
      }
    });
  } catch (error) {
    console.log(`SMOKE:FCM_ERROR setup ${trimMarkerDetail(getErrorMessage(error))}`);
  }
};

configureFcmMode();

registerRootComponent(App);
