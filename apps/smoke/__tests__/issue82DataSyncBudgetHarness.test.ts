import { EventType, AndroidForegroundServiceType } from 'react-native-notify-kit';
import type { Notification } from 'react-native-notify-kit';
import { createIssue82Harness } from '../issue82DataSyncBudgetHarness';
import type { Issue82Event } from '../issue82DataSyncBudgetHarness';

function fixture() {
  const stored = new Map<string, Notification>();
  const api = {
    createChannel: jest.fn(async () => 'channel'),
    displayNotification: jest.fn(async (n: Notification) => {
      stored.set(n.id!, n);
      return n.id!;
    }),
    getDisplayedNotifications: jest.fn(async () =>
      [...stored.values()].map(notification => ({
        id: notification.id!,
        notification,
        trigger: undefined,
      })),
    ),
    cancelNotification: jest.fn(async (id: string) => {
      stored.delete(id);
    }),
    stopForegroundService: jest.fn(async () => {
      for (const [id, n] of stored) {
        if (n.android?.asForegroundService) {
          stored.delete(id);
        }
      }
    }),
  };
  const appState = { currentState: 'active' };
  const otherRunning = jest.fn(() => false);
  const armedChanged = jest.fn();
  const markers: Array<Record<string, unknown>> = [];
  const h = createIssue82Harness({
    api,
    appState,
    otherRunning,
    armedChanged,
    isAndroid: () => true,
    emit: (marker, data) => markers.push({ marker, ...data }),
    nonce: (() => {
      let i = 0;
      return () => `nonce${++i}`;
    })(),
  });
  const control = () => [...stored.values()].find(n => n.data?.role === 'control')!;
  const event = (action = 'issue82-start', n = control()): Issue82Event => ({
    type: EventType.ACTION_PRESS,
    detail: { notification: n, pressAction: { id: action } },
  });
  const arm = async () => {
    await h.arm('run-a');
    appState.currentState = 'background';
  };
  return { h, api, stored, appState, otherRunning, armedChanged, markers, control, event, arm };
}

const crypto82 = jest.requireActual('node:crypto');
const Buffer82 = jest.requireActual('node:buffer').Buffer;
const keys82 = crypto82.generateKeyPairSync('rsa', { modulusLength: 2048 });
const public82 = keys82.publicKey.export({ format: 'jwk' });
const session82 = {
  version: 1,
  sessionId: 'session82',
  serial: 'device',
  build: 'android16',
  bootId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  candidate: 'a'.repeat(64),
  apkHash: 'b'.repeat(64),
  package: 'com.notifeeexample',
  uid: 11794,
  modulus: Buffer82.from(public82.n, 'base64url').toString('hex'),
};
function canonical82(value: unknown): string {
  if (Array.isArray(value)) {
    return '[' + value.map(canonical82).join(',') + ']';
  }
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    return (
      '{' +
      Object.keys(object)
        .sort()
        .map(k => JSON.stringify(k) + ':' + canonical82(object[k]))
        .join(',') +
      '}'
    );
  }
  return JSON.stringify(value);
}
function receipt82(f: ReturnType<typeof fixture>, change: Record<string, unknown> = {}) {
  const diagnostic = JSON.parse(String(f.control().data?.diagnostic));
  const body = {
    version: 1,
    session: session82,
    correlationId: 'run-a',
    attemptId: f.control().data?.attemptId,
    controlNonce: f.control().data?.nonce,
    epoch: diagnostic.epoch,
    ledger: diagnostic.ledger,
    consumerRunId: diagnostic.consumerRunId,
    issuedAt: Date.now(),
    expiresAt: Date.now() + 30000,
    proofs: diagnostic.ledger.map((entry: Record<string, unknown>, i: number) => ({
      attemptId: entry.attemptId,
      notificationId: entry.notificationId,
      processRunId: entry.processRunId,
      process: { pid: '123', starttime: '50', bootId: session82.bootId, uid: session82.uid },
      service: { identity: `service${i}`, startId: '1', notificationId: entry.notificationId },
      promotion: true,
      terminal: 'stop',
      stopResolved: true,
      absent: 'VERIFIED_ABSENT',
      evidenceHash: 'c'.repeat(64),
    })),
    ...change,
  };
  const payload = canonical82(body);
  return Buffer82.from(
    JSON.stringify({
      payload,
      signature: crypto82.sign('sha256', Buffer82.from(payload), keys82.privateKey).toString('hex'),
    }),
  ).toString('base64url');
}

test('host-assisted RED: promoted quiesced stopped receipt permits cleanup and new ARM', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  expect(f.control().data?.diagnostic).toEqual(expect.any(String));
  const diagnostic = JSON.parse(String(f.control().data?.diagnostic));
  expect(diagnostic.phase).toBe('quiesced');
  expect(diagnostic.ledger[0]).toMatchObject({
    dispatch: 'resolved',
    terminal: 'stop',
    stopResolved: true,
  });
  const old = f.event();
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f);
  await f.h.handle(ack);
  expect(f.control()).toBeUndefined();
  expect(f.markers.some(m => m.marker === 'CLEANUP' && m.certified === true)).toBe(true);
  await f.h.handle(ack);
  await f.h.handle(old);
  expect(f.api.cancelNotification).toHaveBeenCalledTimes(1);
  f.appState.currentState = 'active';
  await f.h.arm('fresh');
});

test('stale runner after certified cleanup cannot rearm isolation', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  const old = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  await f.h.handle(f.event('issue82-stop'));
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f);
  await f.h.handle(ack);
  f.armedChanged.mockClear();
  await f.h.runner(old);
  expect(f.armedChanged).not.toHaveBeenCalledWith(true);
  expect(f.control()).toBeUndefined();
  expect(f.markers.filter(m => m.marker === 'START_REQUESTED')).toHaveLength(1);
});

test('quiescence prevents a late timeout from generating a new admission token', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  const old = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  f.api.stopForegroundService.mockRejectedValueOnce(new Error('STOP unknown'));
  await f.h.handle(f.event('issue82-stop'));
  const original = String(f.control().data?.diagnostic);
  const token = f.control().data?.attemptId;
  await f.h.handle({ type: 9, detail: { notification: old } });
  expect(f.control().data?.attemptId).toBe(token);
  expect(f.control().data?.diagnostic).toBe(original);
});

test('persisted quiescence blocks START after partial stopped-control publication', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  const display = f.api.displayNotification.getMockImplementation()!;
  f.api.displayNotification.mockImplementation(async n => {
    if (n.data?.role === 'control' && n.data?.state === 'stopped') {
      throw new Error('partial write');
    }
    return display(n);
  });
  await f.h.handle(f.event('issue82-stop'));
  expect(f.control().data?.state).toBe('ready');
  expect(JSON.parse(String(f.control().data?.diagnostic)).phase).toBe('quiesced');
  await f.h.handle(f.event());
  expect(f.markers.filter(m => m.marker === 'START_REQUESTED')).toHaveLength(0);
});

test('host-assisted RED: stale epoch, foreign authority, interruption and unsigned ACK preserve control', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  expect(f.control().data?.diagnostic).toEqual(expect.any(String));
  for (const change of [
    { epoch: 'old' },
    { session: { ...session82, uid: 999 } },
    { session: { ...session82, bootId: 'wrong' } },
    { attemptId: 'run-a:2:new' },
    { ledger: [] },
    { expiresAt: 0 },
  ]) {
    const ack = f.event('issue82-cleanup');
    ack.detail.input = receipt82(f, change);
    await f.h.handle(ack);
    expect(f.control()).toBeDefined();
    expect(f.api.cancelNotification).not.toHaveBeenCalled();
  }
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f).slice(0, 50);
  await f.h.handle(ack);
  expect(f.control()).toBeDefined();
  await f.h.handle(f.event('issue82-start'));
  expect(f.markers.filter(m => m.marker === 'START_REQUESTED')).toHaveLength(1);
});

test('host receipt cannot certify delayed/unpromoted, pending, rejected or incomplete ledger', async () => {
  for (const kind of ['unpromoted', 'pending', 'rejected', 'missing', 'bad-proof']) {
    const f = fixture();
    await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
    f.appState.currentState = 'background';
    if (kind === 'rejected') {
      f.api.displayNotification.mockImplementationOnce(async n => {
        f.stored.set(n.id!, n);
        return n.id!;
      });
    }
    await f.h.handle(f.event());
    await f.h.handle(f.event('issue82-stop'));
    const data = f.control().data!;
    const diagnostic = JSON.parse(String(data.diagnostic));
    if (kind === 'pending' || kind === 'rejected') {
      diagnostic.ledger[0].dispatch = kind === 'pending' ? 'started' : 'rejected';
    }
    if (kind === 'missing') {
      diagnostic.ledger = [];
    }
    data.diagnostic = canonical82(diagnostic);
    const ack = f.event('issue82-cleanup');
    const base = JSON.parse(Buffer82.from(receipt82(f), 'base64url').toString());
    const body = JSON.parse(base.payload);
    if (kind === 'unpromoted') {
      body.proofs[0].promotion = false;
    }
    if (kind === 'bad-proof') {
      body.proofs[0].absent = 'UNKNOWN';
    }
    const payload = canonical82(body);
    ack.detail.input = Buffer82.from(
      JSON.stringify({
        payload,
        signature: crypto82
          .sign('sha256', Buffer82.from(payload), keys82.privateKey)
          .toString('hex'),
      }),
    ).toString('base64url');
    await f.h.handle(ack);
    expect(f.api.cancelNotification).not.toHaveBeenCalled();
    expect(f.control()).toBeDefined();
  }
});

test('cold ledger survives, restart revokes prior receipt; incomplete cold history remains UNKNOWN', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  const original = String(f.control().data?.diagnostic);
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f);
  const cold = createIssue82Harness({
    api: f.api,
    appState: f.appState,
    isAndroid: () => true,
    otherRunning: () => false,
    emit: () => {},
    nonce: () => 'cold',
  });
  await cold.handle(ack);
  expect(String(f.control().data?.diagnostic)).toBe(original);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  const d = JSON.parse(original);
  d.ledger = [];
  f.control().data!.diagnostic = canonical82(d);
  await cold.handle(ack);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});

test('timeout + denied START require two distinct certified obligations; prior receipt stays stale', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  const first = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  f.stored.delete(first.id!);
  await f.h.handle({ type: 9, detail: { notification: first } });
  await f.h.handle(f.event());
  // Actual denial has no publication; host must provide its own exact native denial proof.
  for (const [id, n] of f.stored) {
    if (n.android?.asForegroundService) {
      f.stored.delete(id);
    }
  }
  await f.h.handle(f.event('issue82-stop'));
  const ack = f.event('issue82-cleanup');
  const wire = JSON.parse(Buffer82.from(receipt82(f), 'base64url').toString());
  const body = JSON.parse(wire.payload);
  body.proofs[0].terminal = 'timeout';
  body.proofs[1].promotion = false;
  body.proofs[1].terminal = 'denial';
  body.proofs[1].denial = 'tracked_initial_accepted';
  const payload = canonical82(body);
  ack.detail.input = Buffer82.from(
    JSON.stringify({
      payload,
      signature: crypto82.sign('sha256', Buffer82.from(payload), keys82.privateKey).toString('hex'),
    }),
  ).toString('base64url');
  await f.h.handle(ack);
  expect(f.control()).toBeUndefined();
  await f.h.handle({ type: 9, detail: { notification: first } });
  await f.h.handle(ack);
  expect(f.api.cancelNotification).toHaveBeenCalledTimes(1);
});

test('receipt verification cannot cancel replacement control after awaited publication', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f);
  const display = f.api.displayNotification.getMockImplementation()!;
  f.api.displayNotification.mockImplementationOnce(async n => {
    await display(n);
    const d = JSON.parse(String(n.data?.diagnostic));
    d.epoch = 'replacement';
    f.stored.set(n.id!, { ...n, data: { ...n.data, diagnostic: canonical82(d) } });
    return n.id!;
  });
  await f.h.handle(ack);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});

test('deferred native dispatch blocks quiescence until settlement and late publication is stopped', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  const display = f.api.displayNotification.getMockImplementation()!;
  let enter!: () => void;
  let finish!: () => void;
  const entered = new Promise<void>(done => {
    enter = done;
  });
  const pending = new Promise<void>(done => {
    finish = done;
  });
  f.api.displayNotification.mockImplementation(async n => {
    if (n.android?.asForegroundService) {
      enter();
      await pending;
    }
    return display(n);
  });
  const start = f.h.handle(f.event());
  await entered;
  const stop = f.h.handle(f.event('issue82-stop'));
  await Promise.resolve();
  expect(JSON.parse(String(f.control().data?.diagnostic))).toMatchObject({
    phase: 'open',
    ledger: [{ dispatch: 'started' }],
  });
  expect(f.markers.some(m => m.marker === 'QUIESCED')).toBe(false);
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  finish();
  await start;
  await stop;
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.control().data?.nativeOutstanding).toBe('true');
  expect(JSON.parse(String(f.control().data?.diagnostic))).toMatchObject({
    phase: 'quiesced',
    ledger: [{ dispatch: 'resolved', terminal: 'stop' }],
  });
});

test('deferred settlement cannot overwrite same-attempt replacement quiescence', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  const display = f.api.displayNotification.getMockImplementation()!;
  let enter!: () => void;
  let finish!: () => void;
  const entered = new Promise<void>(done => {
    enter = done;
  });
  const pending = new Promise<void>(done => {
    finish = done;
  });
  f.api.displayNotification.mockImplementation(async n => {
    if (n.android?.asForegroundService) {
      enter();
      await pending;
    }
    return display(n);
  });
  const start = f.h.handle(f.event());
  await entered;
  const replacement = JSON.parse(String(f.control().data?.diagnostic));
  replacement.phase = 'quiesced';
  replacement.epoch = 'replacement';
  f.control().data!.diagnostic = canonical82(replacement);
  finish();
  await start;
  expect(JSON.parse(String(f.control().data?.diagnostic))).toEqual(replacement);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});

test('failed final cancellation retains certified control and requires a new STOP epoch', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  const ack = f.event('issue82-cleanup');
  ack.detail.input = receipt82(f);
  f.api.cancelNotification.mockRejectedValueOnce(new Error('interrupted'));
  await f.h.handle(ack);
  expect(f.control()).toBeDefined();
  await f.h.handle(ack);
  expect(f.control()).toBeDefined();
  await f.h.handle(f.event('issue82-stop'));
  await f.h.handle(ack);
  expect(f.control()).toBeDefined();
  const fresh = f.event('issue82-cleanup');
  fresh.detail.input = receipt82(f);
  await f.h.handle(fresh);
  expect(f.control()).toBeUndefined();
});

test('ordinary control has no body Activity, dedicated no-launch action and durable correlation', async () => {
  const f = fixture();
  await f.arm();
  expect(f.control()).toMatchObject({
    id: 'issue82-control-run-a',
    data: { correlationId: 'run-a', attempt: '1', role: 'control' },
    android: { pressAction: null, ongoing: true, autoCancel: false },
  });
  expect(f.control().android?.asForegroundService).not.toBe(true);
  expect(f.control().android?.actions?.[0].pressAction).toEqual({ id: 'issue82-start' });
});
test('foreign events ignored; #82 action uses public dataSync display and resolves separately from persistent runner', async () => {
  const f = fixture();
  await f.arm();
  expect(
    await f.h.handle({ type: EventType.ACTION_PRESS, detail: { notification: { id: 'other' } } }),
  ).toBe(false);
  await f.h.handle(f.event());
  const fg = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  expect(fg).toMatchObject({
    data: { correlationId: 'run-a', attempt: '1' },
    android: {
      asForegroundService: true,
      foregroundServiceTypes: [AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_DATA_SYNC],
      pressAction: null,
    },
  });
  let done = false;
  const work = f.h.runner(fg)!.then(() => {
    done = true;
  });
  await Promise.resolve();
  expect(done).toBe(false);
  expect(f.markers.some(m => m.marker === 'START_PROMISE_SETTLED')).toBe(true);
  expect(f.h.runner({ id: 'foreign' })).toBeNull();
  await f.h.handle(f.event('issue82-stop'));
  await work;
  expect(done).toBe(true);
});
test('duplicate and concurrent taps dispatch once, cold reconstruction cannot replay consumed attempt', async () => {
  const f = fixture();
  await f.arm();
  const e = f.event();
  await Promise.all([f.h.handle(e), f.h.handle(e)]);
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(1);
  const cold = createIssue82Harness({
    api: f.api,
    appState: f.appState,
    otherRunning: () => false,
    isAndroid: () => true,
    emit: () => undefined,
    nonce: () => 'cold',
  });
  await cold.handle(e);
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(1);
});
test('timeout numeric 9 releases runner, changes attempt only, never restarts; stale event cannot affect next attempt', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const fg = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  const work = f.h.runner(fg)!;
  // Native teardown removes the old FGS; a host event never stands in for AMS evidence.
  f.stored.delete(fg.id!);
  await f.h.handle({ type: 9, detail: { notification: fg } });
  await work;
  expect(f.control().data?.attempt).toBe('2');
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(1);
  await f.h.handle(f.event());
  const next = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  expect(next.id).not.toBe(fg.id);
  await f.h.handle({ type: 9, detail: { notification: fg } });
  expect(f.control().data?.attempt).toBe('2');
  expect(f.control().data?.state).toBe('settled');
});
test.each(['active', 'unknown'])('background START rejects app state %s', async state => {
  const f = fixture();
  await f.arm();
  f.appState.currentState = state;
  await f.h.handle(f.event());
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(0);
});
test('foreign FGS and competing scenario prevent START and prevent STOP of unrelated singleton', async () => {
  const f = fixture();
  await f.arm();
  f.stored.set('foreign', {
    id: 'foreign',
    android: { channelId: 'x', asForegroundService: true },
  });
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(0);
});
test('cleanup duplicate is idempotent, stale correlation cannot remove new run', async () => {
  const f = fixture();
  await f.arm();
  const old = f.event('issue82-cleanup');
  await Promise.all([f.h.handle(old), f.h.handle(old)]);
  expect(f.api.cancelNotification).toHaveBeenCalledTimes(1);
  f.appState.currentState = 'active';
  await f.h.arm('run-b');
  f.appState.currentState = 'background';
  await f.h.handle(old);
  expect(f.control().data?.correlationId).toBe('run-b');
});
test('TOP transition during awaited preflight prevents dispatch; dispatch failure never becomes promotion', async () => {
  const f = fixture();
  await f.arm();
  f.api.getDisplayedNotifications.mockImplementationOnce(async () => {
    f.appState.currentState = 'active';
    return [...f.stored.values()].map(notification => ({
      id: notification.id!,
      notification,
      trigger: undefined,
    }));
  });
  await f.h.handle(f.event());
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(0);
  f.appState.currentState = 'background';
  f.api.displayNotification.mockImplementationOnce(async () => {
    throw new Error('storage');
  });
  await f.h.handle(f.event());
  expect(f.markers.some(m => m.marker === 'ERROR')).toBe(true);
});

test('competing #81 scenario during await revokes dispatch and STOP authority', async () => {
  const f = fixture();
  await f.arm();
  f.api.displayNotification.mockImplementationOnce(async n => {
    f.stored.set(n.id!, n);
    f.otherRunning.mockReturnValue(true);
    return n.id!;
  });
  await f.h.handle(f.event());
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(0);
  await f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});
test('stale runner cannot be credited to new attempt; no Promise settlement changes an attempt to ready', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const old = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  f.stored.delete(old.id!);
  await f.h.handle({ type: 9, detail: { notification: old } });
  await f.h.runner(old);
  expect(f.markers.some(m => m.marker === 'RUNNER_STARTED')).toBe(false);
  expect(f.control().data?.attempt).toBe('2');
});
test('inflight STOP waits for display settlement and cannot stop a foreign replacement', async () => {
  const f = fixture();
  await f.arm();
  let finish!: () => void;
  const display = f.api.displayNotification.getMockImplementation()!;
  f.api.displayNotification.mockImplementation(async n => {
    if (n.android?.asForegroundService) {
      await new Promise<void>(resolve => {
        finish = resolve;
      });
    }
    return display(n);
  });
  const start = f.h.handle(f.event());
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
  }
  const stop = f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  // Foreign acquisition appearing in stop's snapshot must prevent singleton STOP.
  f.stored.set('foreign', {
    id: 'foreign',
    android: { channelId: 'x', asForegroundService: true },
  });
  finish();
  await start;
  await stop;
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
});

test('timeout delivered before notification removal prepares token but START remains blocked until removal', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const fg = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  await f.h.handle({ type: 9, detail: { notification: fg } });
  expect(f.control().data?.attempt).toBe('2');
  await f.h.handle(f.event());
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(1);
});
test('real Promise rejection is diagnostic only, never native denial or retry', async () => {
  const f = fixture();
  await f.arm();
  const normal = f.api.displayNotification.getMockImplementation()!;
  f.api.displayNotification.mockImplementation(async n => {
    if (n.android?.asForegroundService) {
      throw new Error('ForegroundServiceStartNotAllowedException at dispatch');
    }
    return normal(n);
  });
  await f.h.handle(f.event());
  await f.h.handle(f.event());
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(1);
  expect(f.markers.filter(m => m.marker === 'START_PROMISE_SETTLED')).toEqual([
    expect.objectContaining({ outcome: 'rejected', promotionProven: false }),
  ]);
  expect(f.markers.some(m => m.marker === 'PROMOTION_DENIED')).toBe(false);
});
test('foreign or malformed timeout data never advances durable attempt', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const fg = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  await f.h.handle({
    type: 9,
    detail: { notification: { ...fg, data: { ...fg.data, nonce: 'foreign' } } },
  });
  expect(f.control().data?.attempt).toBe('1');
});

test('foreground event routing cannot pretend to be the background handler even with background AppState', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event(), 'foreground');
  expect(
    f.api.displayNotification.mock.calls.filter(([n]) => n.android?.asForegroundService),
  ).toHaveLength(0);
  expect(f.markers.some(m => m.marker === 'BACKGROUND_ACTION_RECEIVED')).toBe(false);
});

test('malformed persisted #82 control remains UNKNOWN and blocks re-arming', async () => {
  const f = fixture();
  f.stored.set('damaged', {
    id: 'damaged',
    data: { smokeScenario: 'issue82-datasync-budget', role: 'control' },
    android: { channelId: 'x' },
  });
  await expect(f.h.arm('run-a')).rejects.toThrow('existing_scenario_or_fgs');
  expect(f.api.displayNotification).not.toHaveBeenCalled();
});

test('stale preparation lifetime after channel await cannot publish controls or ARMED', async () => {
  const f = fixture();
  let current = true;
  f.api.createChannel.mockImplementationOnce(async () => {
    current = false;
    return 'channel';
  });
  await expect(f.h.arm('run-a', () => current)).rejects.toThrow('arm_lost_visibility');
  expect(f.api.displayNotification).not.toHaveBeenCalled();
  expect(f.markers.some(m => m.marker === 'ARMED')).toBe(false);
});

// IR82-HARNESS-01: dispatch settlement, publication and teardown are independent.
test('delayed native publication retains control through cleanup and cold reconstruction', async () => {
  const f = fixture();
  await f.arm();
  let held!: Notification;
  const display = f.api.displayNotification.getMockImplementation()!;
  f.api.displayNotification.mockImplementation(async n => {
    if (n.android?.asForegroundService) {
      held = n;
      return n.id!;
    }
    return display(n);
  });
  await f.h.handle(f.event());
  expect(f.markers.some(m => m.marker === 'START_PROMISE_SETTLED')).toBe(true);
  await f.h.handle(f.event('issue82-cleanup'));
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  const cold = createIssue82Harness({
    api: f.api,
    appState: f.appState,
    otherRunning: () => false,
    isAndroid: () => true,
    emit: () => undefined,
    nonce: () => 'cold',
  });
  await cold.handle(f.event('issue82-cleanup'));
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  f.stored.set(held.id!, held);
  expect(f.control()).toBeDefined();
  expect(f.control().data?.nativeOutstanding).toBe('true');
});

test.each(['accepted', 'refused', 'unknown'])(
  'STOP %s never invents native terminal evidence',
  async outcome => {
    const f = fixture();
    await f.arm();
    await f.h.handle(f.event());
    const original = f.control();
    if (outcome === 'refused') {
      f.api.stopForegroundService.mockImplementation(async () => {
        throw new Error('refused');
      });
    } else if (outcome === 'unknown') {
      f.api.stopForegroundService.mockImplementation(async () => undefined);
    }
    await f.h.handle(f.event('issue82-cleanup'));
    expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
    expect(f.api.cancelNotification).not.toHaveBeenCalled();
    expect(f.control().id).toBe(original.id);
    expect(f.control().data?.nativeOutstanding).toBe('true');
  },
);

test('timeout keeps inherited obligations and duplicate cleanup cannot discard them', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const old = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  f.stored.delete(old.id!);
  await f.h.handle({ type: 9, detail: { notification: old } });
  const cleanup = f.event('issue82-cleanup');
  await Promise.all([f.h.handle(cleanup), f.h.handle(cleanup)]);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  expect(f.control().data?.attempt).toBe('2');
  await f.h.runner(old);
  await f.h.handle({ type: 9, detail: { notification: old } });
  expect(f.control().data?.attempt).toBe('2');
});

test('new admission queued during uncertain cleanup stays blocked', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  let finish!: () => void;
  f.api.stopForegroundService.mockImplementation(async () => {
    await new Promise<void>(resolve => {
      finish = resolve;
    });
  });
  const cleanup = f.h.handle(f.event('issue82-cleanup'));
  for (let i = 0; i < 30; i++) await Promise.resolve();
  const admission = f.h.arm('run-b');
  const rejected = admission.then(
    () => {
      throw new Error('unexpected admission');
    },
    error => {
      expect(error).toBeInstanceOf(Error);
    },
  );
  finish();
  await cleanup;
  await rejected;
  expect(f.control().data?.correlationId).toBe('run-a');
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});

test('legacy settled or stopped controls never grant cleanup authority', async () => {
  for (const state of ['settled', 'stopped']) {
    const f = fixture();
    await f.arm();
    const c = f.control();
    const data: NonNullable<Notification['data']> = { ...c.data, state };
    delete data.nativeOutstanding;
    f.stored.set(c.id!, { ...c, data });
    await f.h.handle(f.event('issue82-cleanup'));
    expect(f.api.cancelNotification).not.toHaveBeenCalled();
    expect(f.api.stopForegroundService).not.toHaveBeenCalled();
    expect(f.control()).toBeDefined();
  }
});

test('STOP continuation cannot clean a replacement control or stop a foreign FGS', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const old = f.control();
  const fg = [...f.stored.values()].find(n => n.android?.asForegroundService)!;
  f.api.stopForegroundService.mockImplementationOnce(async () => {
    f.stored.delete(fg.id!);
    f.stored.set(old.id!, {
      ...old,
      data: { ...old.data, attempt: '2', nonce: 'replacement', attemptId: 'run-a:2:replacement' },
    });
    f.stored.set('foreign', {
      id: 'foreign',
      android: { channelId: 'x', asForegroundService: true },
    });
  });
  await f.h.handle(f.event('issue82-cleanup'));
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  await f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.control().data?.attemptId).toBe('run-a:2:replacement');
});

test('Android FGS public reconstruction with numeric ID and no android flag remains owned', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const original = [...f.stored.values()].find(n => n.data?.role === 'fgs')!;
  let hash = 0;
  for (const ch of original.id!) hash = (Math.imul(hash, 31) + ch.charCodeAt(0)) | 0;
  f.stored.set(original.id!, { ...original, id: String(hash), android: { channelId: 'channel' } });
  await f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).toHaveBeenCalledTimes(1);
  expect(f.markers.some(m => m.marker === 'STOP_RESOLVED')).toBe(true);
});

test('reconstructed FGS with wrong numeric identity prevents owned STOP', async () => {
  const f = fixture();
  await f.arm();
  await f.h.handle(f.event());
  const original = [...f.stored.values()].find(n => n.data?.role === 'fgs')!;
  f.stored.set(original.id!, { ...original, id: '0', android: { channelId: 'channel' } });
  await f.h.handle(f.event('issue82-stop'));
  expect(f.api.stopForegroundService).not.toHaveBeenCalled();
  expect(f.markers.some(m => m.marker === 'BLOCKED' && m.reason === 'foreign_fgs')).toBe(true);
});

test('receipt transport admits only signed quiesced current background control and is single use', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  const wire = receipt82(f);
  const receive = (c: string, w: string) =>
    Reflect.apply(Reflect.get(f.h, 'receiveReceipt'), f.h, [c, w]);
  await receive('foreign', wire);
  await receive('run-a', wire.slice(0, 50));
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  f.appState.currentState = 'active';
  await receive('run-a', wire);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
  f.appState.currentState = 'background';
  await receive('run-a', wire);
  expect(f.api.cancelNotification).toHaveBeenCalledTimes(1);
  await receive('run-a', wire);
  expect(f.api.cancelNotification).toHaveBeenCalledTimes(1);
});

test('receipt transport cannot use unsigned data to cancel an open ready session', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.receiveReceipt('run-a', 'cleanup');
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});

test('native instance identity never substitutes for positive AMS promotion proof', async () => {
  const f = fixture();
  await Reflect.apply(f.h.arm, null, ['run-a', () => true, session82]);
  f.appState.currentState = 'background';
  await f.h.handle(f.event());
  await f.h.handle(f.event('issue82-stop'));
  const envelope = JSON.parse(Buffer82.from(receipt82(f), 'base64url').toString());
  const body = JSON.parse(envelope.payload);
  body.proofs[0].service.identityKind = 'app-owned-native-instance';
  body.proofs[0].service.identity = 'native:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:2';
  const payload = canonical82(body);
  const wire = Buffer82.from(
    JSON.stringify({
      payload,
      signature: crypto82.sign('sha256', Buffer82.from(payload), keys82.privateKey).toString('hex'),
    }),
  ).toString('base64url');
  await f.h.receiveReceipt('run-a', wire);
  expect(f.api.cancelNotification).not.toHaveBeenCalled();
});
