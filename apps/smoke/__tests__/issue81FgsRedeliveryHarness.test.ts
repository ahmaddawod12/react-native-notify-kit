import type { Notification, DisplayedNotification } from 'react-native-notify-kit';
import { TriggerType } from 'react-native-notify-kit';
import { createIssue81Harness, extractIssue81DeepLink } from '../issue81FgsRedeliveryHarness';

const flush = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
  }
};

function fixture() {
  const api = {
    createChannel: jest.fn(async () => 'channel'),
    displayNotification: jest.fn(async (_notification: Notification) => 'issue81-fgs'),
    stopForegroundService: jest.fn(async () => undefined),
    getDisplayedNotifications: jest.fn(async (): Promise<DisplayedNotification[]> => []),
    registerForegroundService: jest.fn(
      (_runner: (notification: Notification) => Promise<void>) => undefined,
    ),
  };
  let change: (state: string) => void = () => undefined;
  const remove = jest.fn();
  const appState = {
    currentState: 'active',
    addEventListener: jest.fn((_event, listener) => {
      change = listener;
      return { remove };
    }),
  };
  const events: Array<{ marker: string; [key: string]: unknown }> = [];
  const registered = api.registerForegroundService;
  const otherScenarioRunning = jest.fn(() => false);
  const harness = createIssue81Harness({
    api,
    appState,
    isAndroid: () => true,
    otherScenarioRunning,
    emit: (marker, data) => events.push({ marker, ...data }),
  });
  const fallback = jest.fn(async () => undefined);
  api.registerForegroundService(fallback);
  const runner = registered.mock.calls[0][0];
  const start = (id = 'session-a', extra = '') =>
    harness.execute(extractIssue81DeepLink(`notifykit://issue81/fgs?correlationId=${id}${extra}`)!);
  const stop = (id = 'session-a') =>
    harness.execute(
      extractIssue81DeepLink(`notifykit://issue81/fgs?action=stop&correlationId=${id}`)!,
    );
  return {
    api,
    registered,
    otherScenarioRunning,
    harness,
    events,
    start,
    stop,
    runner,
    fallback,
    remove,
    foreground: () => {
      appState.currentState = 'active';
      change('active');
    },
    background: () => {
      appState.currentState = 'background';
      change('background');
    },
  };
}

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

test('persists beyond 750 ms in background, completing same-ID API updates and holding runner', async () => {
  const f = fixture();
  await f.start();
  let settled = false;
  const task = f.runner(f.api.displayNotification.mock.calls[0][0]).then(() => {
    settled = true;
  });
  f.background();
  jest.advanceTimersByTime(2500);
  await flush();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(settled).toBe(false);
  expect(f.api.displayNotification).toHaveBeenCalledTimes(2);
  expect(f.api.displayNotification.mock.calls.map(call => call[0].id)).toEqual([
    'issue81-fgs',
    'issue81-fgs',
  ]);
  expect(f.events.find(e => e.marker === 'UPDATE_RESOLVED')).toMatchObject({
    completed: 1,
    appState: 'background',
  });
  await f.stop();
  await task;
  expect(settled).toBe(true);
});

test('timer firing and pending or rejected API call never count as completed', async () => {
  const f = fixture();
  await f.start();
  let reject!: (error: Error) => void;
  f.api.displayNotification.mockImplementationOnce(
    () =>
      new Promise((_resolve, fail) => {
        reject = fail;
      }),
  );
  jest.advanceTimersByTime(2000);
  await flush();
  expect(f.events.filter(e => e.marker === 'UPDATE_RESOLVED')).toHaveLength(0);
  reject(new Error('native_denied'));
  await flush();
  expect(f.events.find(e => e.marker === 'UPDATE_FAILED')).toMatchObject({ completed: 0 });
  jest.advanceTimersByTime(8000);
  await flush();
  expect(f.api.displayNotification).toHaveBeenCalledTimes(2);
  await f.stop();
});

test('STOP waits for late display settlement, rejects stale STOP, supports duplicates and fresh session', async () => {
  const f = fixture();
  await f.start();
  await f.start('session-b');
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  let resolve!: (id: string) => void;
  f.api.displayNotification.mockImplementationOnce(
    () =>
      new Promise(done => {
        resolve = done;
      }),
  );
  jest.advanceTimersByTime(2000);
  await flush();
  const stop = f.stop();
  const duplicate = f.stop();
  await flush();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  resolve('issue81-fgs');
  await Promise.all([stop, duplicate]);
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  await f.stop();
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  await f.start('session-b');
  await f.stop('session-a');
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  await f.stop('session-b');
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(2);
  expect(f.remove).toHaveBeenCalledTimes(2);
});

test('limit stops updating but keeps FGS until explicit STOP; deadline performs controlled cleanup', async () => {
  const f = fixture();
  await f.start('session-a', '&maxUpdates=1&safetyTimeoutMs=10000');
  jest.advanceTimersByTime(2000);
  await flush();
  jest.advanceTimersByTime(4000);
  await flush();
  expect(f.api.displayNotification).toHaveBeenCalledTimes(2);
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  jest.advanceTimersByTime(4000);
  await flush();
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.events.some(e => e.marker === 'SAFETY_TIMEOUT')).toBe(true);
});

test('foreign notifications delegate to the existing and subsequently registered runner', async () => {
  const f = fixture();
  await f.start();
  await f.runner({ id: 'foreign' });
  expect(f.fallback).toHaveBeenCalledWith({ id: 'foreign' });
  const newer = jest.fn(async () => undefined);
  f.api.registerForegroundService(newer);
  const router = f.registered.mock.calls[1][0];
  await router({ id: 'issue44-fgs-stop' });
  expect(newer).toHaveBeenCalledWith({ id: 'issue44-fgs-stop' });
  await f.stop();
});

test('cold STOP never stops an unrelated service; invalid configuration has no native effects', async () => {
  const f = fixture();
  await f.stop();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  await f.start('invalid', '&type=shortService');
  await f.start('invalid', '&intervalMs=NaN');
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(extractIssue81DeepLink('notifykit://issue81/fgs-other')).toBeNull();
  expect(extractIssue81DeepLink('notifykit://issue81/fgs?correlationId=%ZZ')?.error).toBeDefined();
});

test('STOP during delayed START drains work and cannot dispatch a late display', async () => {
  const f = fixture();
  let resolve!: (channel: string) => void;
  f.api.createChannel.mockImplementationOnce(
    () =>
      new Promise(done => {
        resolve = done;
      }),
  );
  const starting = f.start();
  await flush();
  const stopping = f.stop();
  await flush();
  resolve('channel');
  await Promise.all([starting, stopping]);
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(f.harness.isActive()).toBe(false);
});

test('failed STOP preserves the slot and runner until explicit retry succeeds', async () => {
  const f = fixture();
  await f.start();
  f.api.stopForegroundService.mockRejectedValueOnce(new Error('stop_failure'));
  await f.stop();
  expect(f.harness.isActive()).toBe(true);
  await f.start('session-b');
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  await f.stop();
  expect(f.harness.isActive()).toBe(false);
});

test('suspended timer records delay and cannot run an update past the wall-clock safety deadline', async () => {
  const f = fixture();
  await f.start();
  jest.setSystemTime(Date.now() + 200000);
  expect(f.events.filter(e => e.marker === 'UPDATE_RESOLVED')).toHaveLength(0);
  jest.advanceTimersByTime(2000);
  await flush();
  expect(f.events.some(e => e.marker === 'TIMER_DELAYED')).toBe(true);
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
});

test('correlation reuse is blocked so a repeated stale STOP cannot terminate a new session', async () => {
  const f = fixture();
  await f.start();
  const oldNotification = f.api.displayNotification.mock.calls[0][0];
  await f.stop();
  await f.start();
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  await f.start('session-b');
  await f.runner(oldNotification);
  await f.stop('session-a');
  expect(f.harness.isActive()).toBe(true);
  await f.stop('session-b');
});

test('foreign service callback halts updates and own cleanup never stops the foreign owner', async () => {
  const f = fixture();
  await f.start();
  await f.runner({ id: 'foreign' });
  jest.advanceTimersByTime(6000);
  await flush();
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  await f.stop();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});

test('initial URL plus URL event duplicate START is idempotent and emits no BLOCKED result', async () => {
  const f = fixture();
  await f.start();
  await f.start();
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  expect(f.events.some(e => e.marker === 'START_DUPLICATE')).toBe(true);
  expect(f.events.some(e => e.marker === 'BLOCKED')).toBe(false);
  await f.stop();
});

test('a deep link arriving before Activity resume waits for real foreground admission', async () => {
  const f = fixture();
  f.background();
  const starting = f.start();
  await flush();
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  f.foreground();
  await starting;
  expect(f.api.displayNotification).toHaveBeenCalledTimes(1);
  expect(f.events.some(e => e.marker === 'WAITING_FOR_FOREGROUND')).toBe(true);
  await f.stop();
});

test('STOP cancels foreground admission and cannot cause late native START', async () => {
  const f = fixture();
  f.background();
  const starting = f.start();
  await flush();
  await f.stop();
  f.foreground();
  await starting;
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.harness.isActive()).toBe(false);
});

test('foreground admission timeout is bounded and does no native dispatch', async () => {
  const f = fixture();
  f.background();
  const starting = f.start();
  await flush();
  jest.advanceTimersByTime(5000);
  await starting;
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.events.find(e => e.marker === 'BLOCKED')).toMatchObject({
    reason: 'foreground_timeout',
  });
});

function persistedOwn(): DisplayedNotification[] {
  return [
    {
      id: 'issue81-fgs',
      trigger: { type: TriggerType.TIMESTAMP, timestamp: 1 },
      notification: {
        id: 'issue81-fgs',
        data: { smokeScenario: 'issue81-fgs', correlationId: 'session-a' },
        android: { asForegroundService: true },
      },
    },
  ];
}

test('cold cleanup reserves admission across notification lookup and safely stops only its own persisted FGS', async () => {
  const f = fixture();
  let read!: (value: DisplayedNotification[]) => void;
  f.api.getDisplayedNotifications.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        read = resolve;
      }),
  );
  const cold = f.stop();
  await flush();
  expect(f.harness.isActive()).toBe(true);
  await f.start();
  await f.stop();
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  read(persistedOwn());
  await cold;
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.harness.isActive()).toBe(false);
});

test('cold cleanup never adopts an ordinary same-ID notification or a foreign FGS', async () => {
  const f = fixture();
  const ordinary = persistedOwn();
  ordinary[0].notification.android!.asForegroundService = false;
  f.api.getDisplayedNotifications.mockResolvedValueOnce(ordinary);
  await f.stop();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  f.api.getDisplayedNotifications.mockResolvedValueOnce([
    ...persistedOwn(),
    {
      id: 'foreign',
      trigger: { type: TriggerType.TIMESTAMP, timestamp: 1 },
      notification: { id: 'foreign', android: { asForegroundService: true } },
    },
  ]);
  await f.stop();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(f.harness.isActive()).toBe(false);
});

test('foreign runner during pending channel creation fences the late START display', async () => {
  const f = fixture();
  let release!: (channel: string) => void;
  f.api.createChannel.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        release = resolve;
      }),
  );
  const starting = f.start();
  await flush();
  await f.runner({ id: 'foreign' });
  release('channel');
  await starting;
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});

test('a delayed START callback cannot dispatch display beyond the wall-clock safety deadline', async () => {
  const f = fixture();
  let release!: (channel: string) => void;
  f.api.createChannel.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        release = resolve;
      }),
  );
  const starting = f.start();
  await flush();
  jest.setSystemTime(Date.now() + 200000);
  release('channel');
  await starting;
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.harness.isActive()).toBe(false);
});

test.each(['foreign_callback', 'runner_replacement', 'stale_own_callback'])(
  'cold lookup cannot authorize global STOP after %s',
  async interference => {
    const f = fixture();
    let read!: (rows: DisplayedNotification[]) => void;
    f.api.getDisplayedNotifications.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          read = resolve;
        }),
    );
    const stopping = f.stop();
    await flush();
    if (interference === 'runner_replacement') {
      f.api.registerForegroundService(jest.fn(async () => undefined));
    } else {
      const notification =
        interference === 'foreign_callback'
          ? { id: 'foreign', android: { asForegroundService: true } }
          : {
              ...persistedOwn()[0].notification,
              data: { smokeScenario: 'issue81-fgs', correlationId: 'old-session' },
            };
      await f.runner(notification);
      if (interference === 'foreign_callback') {
        expect(f.fallback).toHaveBeenCalledWith(notification);
      }
    }
    read(persistedOwn());
    await stopping;
    expect(f.api.stopForegroundService).not.toHaveBeenCalled();
    expect(f.harness.isActive()).toBe(false);
  },
);

test.each(['foreign_callback', 'runner_replacement'])(
  '%s after cold adoption but before drained STOP revokes cleanup',
  async interference => {
    const f = fixture();
    let read!: (rows: DisplayedNotification[]) => void;
    f.api.getDisplayedNotifications.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          read = resolve;
        }),
    );
    const stopping = f.stop();
    await flush();
    read(persistedOwn());
    // Run adoption, then intervene during stop's await of drained work.
    await Promise.resolve();
    if (interference === 'runner_replacement') {
      f.api.registerForegroundService(jest.fn(async () => undefined));
    } else {
      await f.runner({ id: 'foreign' });
    }
    await stopping;
    expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  },
);

test('runner replacement during drained display cannot dispatch stale native STOP', async () => {
  const f = fixture();
  await f.start();
  let release!: (id: string) => void;
  f.api.displayNotification.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        release = resolve;
      }),
  );
  jest.advanceTimersByTime(2000);
  await flush();
  const stopping = f.stop();
  f.api.registerForegroundService(jest.fn(async () => undefined));
  release('issue81-fgs');
  await stopping;
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(f.harness.isActive()).toBe(false);
  expect(f.remove).toHaveBeenCalledTimes(1);
});

test('cold readback with a foreign same-ID FGS never grants global STOP', async () => {
  const f = fixture();
  const foreign = persistedOwn();
  foreign[0].notification.data!.correlationId = 'foreign-session';
  f.api.getDisplayedNotifications.mockResolvedValueOnce([...persistedOwn(), ...foreign]);
  await f.stop();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});

test('ownership predicate changing after cold adoption revokes native STOP', async () => {
  const f = fixture();
  let read!: (rows: DisplayedNotification[]) => void;
  f.api.getDisplayedNotifications.mockImplementationOnce(
    () =>
      new Promise(resolve => {
        read = resolve;
      }),
  );
  const stopping = f.stop();
  await flush();
  read(persistedOwn());
  await Promise.resolve();
  f.otherScenarioRunning.mockReturnValue(true);
  await stopping;
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});
