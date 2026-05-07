# NotifyKit Expo Smoke

Minimal Expo CNG fixture for validating `react-native-notify-kit` package resolution, Expo config, prebuild, and the basic local notification runtime path.

This app is intentionally separate from `apps/smoke`, which remains the React Native bare smoke app.

This fixture is for Expo CNG, prebuild, and development builds; it is not intended for Expo Go.

## Scope

- Expo SDK 55 development build flow.
- Local workspace dependency: `react-native-notify-kit: workspace:*`.
- Basic `requestPermission`, Android `createChannel`, `displayNotification`, foreground event, and `getDisplayedNotifications` checks.
- Config plugin resolution with iOS Notification Service Extension config validation and EAS `appExtensions` metadata.
- No native Notification Service Extension generation, FCM, Firebase setup, or vendor notification SDK yet.

## Commands

Run from the repository root:

```sh
yarn smoke:expo:config
yarn smoke:expo:prebuild:ios
yarn smoke:expo:prebuild:android
yarn smoke:expo:start
```

The generated `ios/`, `android/`, and `.expo/` directories are ignored because this fixture follows Expo Continuous Native Generation. The source of truth is `app.config.ts` plus the JS/TS files in this directory.
