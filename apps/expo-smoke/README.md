# NotifyKit Expo Smoke

Manual Expo CNG fixture for validating `react-native-notify-kit` package resolution, Expo config, prebuild, development builds, and the basic local notification runtime path.

This app is intentionally separate from `apps/smoke`, which remains the full React Native bare smoke app. This fixture is for Expo CNG, prebuild, and development builds; it is not intended for Expo Go.

## Scope

- Expo SDK 55 development build flow.
- Local workspace dependency: `react-native-notify-kit: workspace:*`.
- Manual runtime checks for `getNotificationSettings`, `requestPermission`, Android channel creation/readback, `displayNotification`, `getDisplayedNotifications`, foreground `DELIVERED`/`PRESS`, `cancelNotification`, and `cancelAllNotifications`.
- Config plugin resolution with iOS Notification Service Extension config validation and EAS `appExtensions` metadata.
- No FCM, RNFirebase, Firebase plist/json files, deep links, callback HTTP server, trigger stress, foreground service, exact alarms, reboot recovery, or advanced Android action suite.

## Commands

Run from the repository root:

```sh
yarn smoke:expo:config
yarn smoke:expo:prebuild:ios
yarn smoke:expo:prebuild:android
yarn smoke:expo:start
```

Run app-local commands from this workspace when needed:

```sh
yarn workspace react-native-notify-kit-expo-smoke config
yarn workspace react-native-notify-kit-expo-smoke prebuild:ios
yarn workspace react-native-notify-kit-expo-smoke prebuild:android
yarn workspace react-native-notify-kit-expo-smoke start
```

The generated `ios/`, `android/`, and `.expo/` directories are ignored because this fixture follows Expo Continuous Native Generation. The source of truth is `app.config.ts` plus the JS/TS files in this directory.

## Runtime Markers

The app writes short `SMOKE:*` markers to the Metro/device console and a readable summary to the on-screen log:

- `SMOKE:APP_STARTED`
- `SMOKE:NOTIFEE_IMPORTED`
- `SMOKE:SETTINGS_OK`
- `SMOKE:PERMISSION_OK`
- `SMOKE:CHANNEL_CREATED`
- `SMOKE:CHANNELS_COUNT`
- `SMOKE:DISPLAY_LOCAL_OK`
- `SMOKE:DISPLAYED_COUNT`
- `SMOKE:FOREGROUND_EVENT_DELIVERED`
- `SMOKE:FOREGROUND_EVENT_PRESS`
- `SMOKE:CANCEL_OK`
- `SMOKE:CANCEL_ALL_OK`
- `SMOKE:ERROR`

## Manual iOS Check

1. Run `yarn smoke:expo:prebuild:ios`.
2. Start the development client with `yarn smoke:expo:start`, then open the app in the iOS development build.
3. Confirm `SMOKE:APP_STARTED` and `SMOKE:NOTIFEE_IMPORTED` appear.
4. Press `Get notification settings`, `Request permission`, `Display local notification`, `Get displayed notifications`, `Cancel last notification`, and `Cancel all notifications`.
5. Confirm the on-screen log shows concise results and Metro/device logs contain the expected `SMOKE:*` markers.

## Manual Android Check

Android smoke base will be used to verify runtime package resolution, autolinking, Android channels, and local notifications. There is not yet Android config plugin automation in this fixture.

1. Run `yarn smoke:expo:prebuild:android`.
2. From `apps/expo-smoke`, run `npx expo run:android` against an emulator or device.
3. Start Metro with `yarn smoke:expo:start` if the run command does not start it.
4. Confirm startup markers, then press `Ensure Android channel`, `Display local notification`, `Get displayed notifications`, `Cancel last notification`, and `Cancel all notifications`.
5. Confirm `SMOKE:CHANNEL_CREATED`, `SMOKE:CHANNELS_COUNT`, `SMOKE:DISPLAY_LOCAL_OK`, `SMOKE:DISPLAYED_COUNT`, and cancel markers appear in logs.
