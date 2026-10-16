import { Alert, AppRegistry, Platform } from 'react-native';
import notifee, { EventType } from 'react-native-notify-kit';
import { issue82Harness } from '../issue82DataSyncBudgetHarness';

test('index uses exactly one background handler; #82 headless branch skips Alert and foreign branch is unchanged', async () => {
  Object.defineProperty(Platform, 'OS', { value: 'android' });
  jest.spyOn(AppRegistry, 'registerComponent').mockImplementation(() => 'NotifeeExample');
  const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
  const route = jest.spyOn(issue82Harness, 'handle').mockResolvedValue(true);
  const register = notifee.onBackgroundEvent as jest.Mock;
  register.mockClear();
  const nativeRegister = notifee.registerForegroundService as jest.Mock;
  jest.requireActual('../index');
  (
    globalThis as typeof globalThis & { __ISSUE82_TEST_ROUTER__?: (n: unknown) => Promise<void> }
  ).__ISSUE82_TEST_ROUTER__ = nativeRegister.mock.calls.at(-1)![0];
  expect(register).toHaveBeenCalledTimes(1);
  const background = register.mock.calls[0][0];
  const e = {
    type: EventType.ACTION_PRESS,
    detail: { notification: { id: 'control' }, pressAction: { id: 'issue82-start' } },
  };
  await background(e);
  expect(route).toHaveBeenCalledWith(e);
  expect(alert).not.toHaveBeenCalled();
  route.mockResolvedValue(false);
  await background(e);
  expect(alert).toHaveBeenCalledTimes(1);
  jest.restoreAllMocks();
});

test('index FGS routing preserves default and #81 fallback, keeping #82 work persistent', async () => {
  // #81 wraps this method; index's default was passed through to the underlying mock.
  const original = (
    globalThis as typeof globalThis & { __ISSUE82_TEST_ROUTER__?: (n: unknown) => Promise<void> }
  ).__ISSUE82_TEST_ROUTER__!;
  const router = original;
  let release!: () => void;
  const work = new Promise<void>(resolve => {
    release = resolve;
  });
  const spy = jest.spyOn(issue82Harness, 'runner').mockReturnValue(work);
  let done = false;
  const task = router({
    id: 'issue82-fgs',
    data: { smokeScenario: 'issue82-datasync-budget' },
  }).then(() => {
    done = true;
  });
  await Promise.resolve();
  expect(done).toBe(false);
  release();
  await task;
  spy.mockReturnValue(null);
  await expect(router({ id: 'other' })).resolves.toBeUndefined();
  jest.restoreAllMocks();
});

test('persisted control blocks #81 and #44 before runner re-registration even without in-memory reservation', async () => {
  const { createIssue81Harness, extractIssue81DeepLink } = jest.requireActual(
    '../issue81FgsRedeliveryHarness',
  );
  const { executeIssue44FgsStopDeepLink, extractIssue44FgsStopDeepLink } = jest.requireActual(
    '../issue44FgsStopHarness',
  );
  const globals = globalThis as typeof globalThis & { __NOTIFEE_ISSUE82_ARMED__?: boolean };
  globals.__NOTIFEE_ISSUE82_ARMED__ = false;
  const displayed = jest
    .spyOn(notifee, 'getDisplayedNotifications')
    .mockResolvedValue([
      { notification: { id: 'ctrl', data: { smokeScenario: 'issue82-datasync-budget' } } },
    ] as never);
  const display = jest.spyOn(notifee, 'displayNotification');
  display.mockClear();
  const register = jest.spyOn(notifee, 'registerForegroundService');
  register.mockClear();
  const h = createIssue81Harness({
    api: notifee,
    appState: { currentState: 'active', addEventListener: () => ({ remove: () => undefined }) },
    isAndroid: () => true,
    otherScenarioRunning: () => false,
    emit: () => undefined,
  });
  await h.execute(extractIssue81DeepLink('notifykit://issue81/fgs?correlationId=blocked82'));
  await executeIssue44FgsStopDeepLink(
    extractIssue44FgsStopDeepLink('notifykit://issue44/fgs-stop?types=dataSync'),
  );
  expect(display).not.toHaveBeenCalled();
  expect(register).not.toHaveBeenCalled();
  displayed.mockRestore();
  jest.restoreAllMocks();
});

test('alarm PoC keeps only its explicitly tagged native run pending', async () => {
  Object.defineProperty(Platform, 'OS', { value: 'android' });
  jest.requireActual('../index');
  const router = (
    globalThis as typeof globalThis & { __ISSUE82_TEST_ROUTER__?: (n: unknown) => Promise<void> }
  ).__ISSUE82_TEST_ROUTER__!;
  let completed = false;
  const pendingWork = router({
    id: 'issue82-alarm-run',
    data: { smokeScenario: 'issue82-alarm-poc', run: 'run' },
  }).then(() => {
    completed = true;
  });
  await Promise.resolve();
  await Promise.resolve();
  expect(pendingWork).toBeInstanceOf(Promise);
  expect(completed).toBe(false);
  await expect(
    router({
      id: 'issue82-alarm-other',
      data: { smokeScenario: 'issue82-alarm-poc', run: 'run' },
    }),
  ).resolves.toBeUndefined();
  await expect(router({ id: 'ordinary' })).resolves.toBeUndefined();
});
