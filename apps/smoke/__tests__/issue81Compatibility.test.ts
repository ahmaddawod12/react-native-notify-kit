import type AppComponent from '../App';
import type { AppStateStatus } from 'react-native';
import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { AppState, Linking, PermissionsAndroid, Platform } from 'react-native';
import notifee from 'react-native-notify-kit';
import type { Notification } from 'react-native-notify-kit';
import {
  createIssue81Harness,
  extractIssue81DeepLink,
  installIssue81SmokeRouter,
} from '../issue81FgsRedeliveryHarness';
import {
  executeIssue44FgsStopDeepLink,
  extractIssue44FgsStopDeepLink,
} from '../issue44FgsStopHarness';

const flush = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
  }
};

const originalRegister = notifee.registerForegroundService;

test('unchanged #44 runner and 750-ms STOP still work before and after a persistent #81 session', async () => {
  jest.useFakeTimers();
  Object.defineProperty(Platform, 'OS', { value: 'android' });
  Object.defineProperty(Platform, 'Version', { value: 37 });
  Object.defineProperty(AppState, 'currentState', { value: 'active', writable: true });
  let change: (state: AppStateStatus) => void = () => undefined;
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
    change = listener;
    return { remove: jest.fn() };
  });
  jest.spyOn(PermissionsAndroid, 'check').mockResolvedValue(true);
  const register = notifee.registerForegroundService as jest.Mock;
  const display = notifee.displayNotification as jest.Mock;
  const stop = notifee.stopForegroundService as jest.Mock;
  const globals = globalThis as typeof globalThis & { __NOTIFEE_ISSUE44_RUNNING__?: boolean };
  const harness = createIssue81Harness({
    api: notifee,
    appState: AppState,
    isAndroid: () => true,
    otherScenarioRunning: () => Boolean(globals.__NOTIFEE_ISSUE44_RUNNING__),
    emit: jest.fn(),
  });
  const request44 = extractIssue44FgsStopDeepLink('notifykit://issue44/fgs-stop')!;
  try {
    const first = executeIssue44FgsStopDeepLink(request44);
    await flush();
    const router = register.mock.calls.at(-1)![0];
    let settled = false;
    const task44 = router(display.mock.calls.at(-1)![0] as Notification).then(() => {
      settled = true;
    });
    await harness.execute(extractIssue81DeepLink('notifykit://issue81/fgs?correlationId=blocked')!);
    expect(display).toHaveBeenCalledTimes(1);
    change('background');
    await flush();
    jest.advanceTimersByTime(749);
    await flush();
    expect(stop).not.toHaveBeenCalled();
    expect(settled).toBe(false);
    jest.advanceTimersByTime(1);
    await first;
    await task44;
    expect(stop).toHaveBeenCalledTimes(1);
    expect(settled).toBe(true);
    await harness.execute(extractIssue81DeepLink('notifykit://issue81/fgs?correlationId=own')!);
    jest.advanceTimersByTime(2000);
    await flush();
    expect(display.mock.calls.at(-1)![0].id).toBe('issue81-fgs');
    await harness.execute(
      extractIssue81DeepLink('notifykit://issue81/fgs?action=stop&correlationId=own')!,
    );
    const second = executeIssue44FgsStopDeepLink(request44);
    await flush();
    expect(display.mock.calls.at(-1)![0].id).toBe('issue44-fgs-stop');
    change('background');
    await flush();
    jest.advanceTimersByTime(750);
    await second;
    expect(stop).toHaveBeenCalledTimes(3);
  } finally {
    notifee.registerForegroundService = originalRegister;
    jest.useRealTimers();
    jest.restoreAllMocks();
  }
});

test.each(['unmount', 'remount', 'valid', 'duplicate_event'])(
  'initial URL lifetime: %s',
  async scenario => {
    jest.useFakeTimers();
    Object.defineProperty(Platform, 'OS', { value: 'android' });
    Object.defineProperty(AppState, 'currentState', { value: 'active', writable: true });
    jest.spyOn(PermissionsAndroid, 'check').mockResolvedValue(true);
    (notifee.getDisplayedNotifications as jest.Mock).mockResolvedValue([]);
    (notifee.createChannel as jest.Mock).mockResolvedValue('channel');
    (notifee.displayNotification as jest.Mock).mockResolvedValue('issue81-fgs');
    let release!: (url: string) => void;
    jest.spyOn(Linking, 'getInitialURL').mockReturnValue(
      new Promise(resolve => {
        release = resolve;
      }),
    );
    const callbacks: Array<(event: { url: string }) => void> = [];
    const removes: jest.Mock[] = [];
    jest.spyOn(Linking, 'addEventListener').mockImplementation((_event, listener) => {
      callbacks.push(listener);
      const remove = jest.fn();
      removes.push(remove);
      return { remove } as unknown as ReturnType<typeof Linking.addEventListener>;
    });
    const App = jest.requireActual<{ default: typeof AppComponent }>('../App').default;
    const harness = installIssue81SmokeRouter();
    const execute = jest.spyOn(harness, 'execute');
    const cid = `lifetime-${scenario}`;
    const url = `notifykit://issue81/fgs?correlationId=${cid}`;
    let app!: ReactTestRenderer.ReactTestRenderer;
    try {
      await ReactTestRenderer.act(async () => {
        app = ReactTestRenderer.create(React.createElement(App));
      });
      execute.mockClear();
      (notifee.displayNotification as jest.Mock).mockClear();
      const staleCallbacks = [...callbacks];
      const staleRemoves = [...removes];
      if (scenario === 'unmount' || scenario === 'remount') {
        await ReactTestRenderer.act(async () => {
          app.unmount();
        });
        if (scenario === 'remount') {
          jest.spyOn(Linking, 'getInitialURL').mockResolvedValue(null);
          await ReactTestRenderer.act(async () => {
            app = ReactTestRenderer.create(React.createElement(App));
          });
        }
      }
      await ReactTestRenderer.act(async () => {
        if (scenario === 'duplicate_event') {
          callbacks.forEach(callback => callback({ url }));
        }
        release(url);
        await flush();
      });
      if (scenario === 'unmount' || scenario === 'remount') {
        staleCallbacks.forEach(callback => callback({ url }));
        await flush();
        expect(execute).not.toHaveBeenCalled();
        expect(notifee.displayNotification).not.toHaveBeenCalled();
        expect(staleRemoves.every(remove => remove.mock.calls.length === 1)).toBe(true);
      } else {
        expect(notifee.displayNotification).toHaveBeenCalledTimes(1);
        expect(harness.isActive()).toBe(true);
      }
    } finally {
      await harness.execute(
        extractIssue81DeepLink(`notifykit://issue81/fgs?action=stop&correlationId=${cid}`)!,
      );
      if (scenario !== 'unmount') {
        await ReactTestRenderer.act(async () => {
          app.unmount();
        });
      }
      jest.restoreAllMocks();
      jest.useRealTimers();
    }
  },
);
