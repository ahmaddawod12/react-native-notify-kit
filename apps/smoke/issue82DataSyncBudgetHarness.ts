import { AppState, Platform } from 'react-native';
import notifee, {
  AndroidForegroundServiceType,
  AndroidImportance,
  EventType,
} from 'react-native-notify-kit';
import type { Event, Notification } from 'react-native-notify-kit';
import { smokeErrorReason } from './smokeAutomation';
import { canonical82, readDiagnostic82, validSession82, verifyReceipt82 } from './issue82Receipt';
import type { Diagnostic82, Session82 } from './issue82Receipt';

const SCENARIO = 'issue82-datasync-budget';
const CHANNEL = 'issue82_datasync_budget';
const START = 'issue82-start';
const STOP = 'issue82-stop';
const CLEANUP = 'issue82-cleanup';
export type Issue82Event = Omit<Event, 'type'> & { type: number };
type Api = Pick<
  typeof notifee,
  'createChannel' | 'displayNotification' | 'cancelNotification' | 'stopForegroundService'
> & { getDisplayedNotifications: () => Promise<Array<{ notification: Notification }>> };
type State = 'ready' | 'pending' | 'settled' | 'error' | 'stopped';
type Record82 = {
  correlationId: string;
  attempt: number;
  nonce: string;
  state: State;
  nativeOutstanding: boolean;
  diagnostic: Diagnostic82 | null;
};
const validId = (id: unknown): id is string =>
  typeof id === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(id);
const controlId = (r: Record82) => `issue82-control-${r.correlationId}`;
const attemptId = (r: Record82) => `${r.correlationId}:${r.attempt}:${r.nonce}`;
const fgsId = (r: Record82) => `issue82-fgs-${r.correlationId}-${r.attempt}-${r.nonce}`;
function androidId82(id: string): string {
  let hash = 0;
  for (let i = 0; i < id.length; i++) hash = (Math.imul(hash, 31) + id.charCodeAt(i)) | 0;
  return String(hash);
}
function read(n?: Notification): Record82 | null {
  const d = n?.data;
  if (
    d?.smokeScenario !== SCENARIO ||
    !validId(d.correlationId) ||
    !validId(d.nonce) ||
    !/^[1-9]\d{0,5}$/.test(String(d.attempt)) ||
    !['ready', 'pending', 'settled', 'error', 'stopped'].includes(String(d.state))
  ) {
    return null;
  }
  const r = {
    correlationId: d.correlationId,
    attempt: Number(d.attempt),
    nonce: d.nonce,
    state: d.state as State,
    // Legacy or inconsistent records carry UNKNOWN native obligations.
    nativeOutstanding: !(
      d.nativeOutstanding === 'false' &&
      d.state === 'ready' &&
      Number(d.attempt) === 1
    ),
    diagnostic: readDiagnostic82(d.diagnostic),
  };
  return d.attemptId === attemptId(r) &&
    (d.role === 'control'
      ? n?.id === controlId(r)
      : d.role === 'fgs' && (n?.id === fgsId(r) || n?.id === androidId82(fgsId(r))))
    ? r
    : null;
}
export function createIssue82Harness(deps: {
  api: Api;
  appState: { readonly currentState: string | null };
  isAndroid: () => boolean;
  otherRunning: () => boolean;
  emit: (marker: string, data: Record<string, unknown>) => void;
  nonce: () => string;
  armedChanged?: (armed: boolean) => void;
}) {
  const processRunId = `${Date.now()}-${deps.nonce()}`;
  let tail: Promise<unknown> = Promise.resolve();
  const runners = new Map<string, { work: Promise<void>; resolve: () => void }>();
  const serial = <T>(fn: () => Promise<T>): Promise<T> => {
    const result = tail.then(fn);
    tail = result.catch(() => undefined);
    return result;
  };
  const log = (marker: string, r: Record82, extra: Record<string, unknown> = {}) =>
    deps.emit(marker, {
      smokeScenario: SCENARIO,
      correlationId: r.correlationId,
      attempt: r.attempt,
      attemptId: attemptId(r),
      notificationId: fgsId(r),
      controlId: controlId(r),
      appState: deps.appState.currentState,
      processRunId,
      loggedAt: Date.now(),
      fgsType: 1,
      requestState: r.state,
      nativeOutstanding: r.nativeOutstanding,
      ...extra,
    });
  const data = (r: Record82, role: string) => ({
    smokeScenario: SCENARIO,
    role,
    correlationId: r.correlationId,
    attempt: String(r.attempt),
    nonce: r.nonce,
    attemptId: attemptId(r),
    state: r.state,
    nativeOutstanding: String(r.nativeOutstanding),
    ...(r.diagnostic ? { diagnostic: canonical82(r.diagnostic) } : {}),
  });
  const control = (r: Record82): Notification => ({
    id: controlId(r),
    title: `Issue 82 ${r.correlationId}`,
    body: `Attempt ${r.attempt}: ${r.state}`,
    data: data(r, 'control'),
    android: {
      channelId: CHANNEL,
      ongoing: true,
      autoCancel: false,
      pressAction: null,
      onlyAlertOnce: true,
      actions: [
        { title: 'Start dataSync FGS', pressAction: { id: START } },
        { title: 'Stop owned FGS', pressAction: { id: STOP } },
        {
          title: 'Cleanup',
          pressAction: { id: CLEANUP },
          ...(r.diagnostic
            ? {
                input: { placeholder: 'Host signed receipt', allowFreeFormInput: true },
                autoCancel: false,
              }
            : {}),
        },
      ],
    },
  });
  const foreground = (r: Record82): Notification => ({
    id: fgsId(r),
    title: `Issue 82 ${r.correlationId}`,
    body: `dataSync ${attemptId(r)}`,
    data: data(r, 'fgs'),
    android: {
      channelId: CHANNEL,
      ongoing: true,
      pressAction: null,
      asForegroundService: true,
      foregroundServiceTypes: [AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_DATA_SYNC],
    },
  });
  const release = (r: Record82) => {
    runners.get(attemptId(r))?.resolve();
    runners.delete(attemptId(r));
  };
  async function snapshot() {
    const displayed = await deps.api.getDisplayedNotifications();
    return {
      controls: displayed.map(x => x.notification).filter(n => n.data?.smokeScenario === SCENARIO),
      // Framework FGS notifications can be reconstructed without the original
      // NotifyKit bundle. Keep scenario FGS records, including malformed/foreign
      // identities, so ownership checks fail closed before STOP.
      fgs: displayed
        .map(x => x.notification)
        .filter(
          n =>
            n.android?.asForegroundService ||
            (n.data?.smokeScenario === SCENARIO && n.data?.role === 'fgs'),
        ),
    };
  }
  function current(controls: Notification[], r: Record82) {
    const found = controls.find(n => n.id === controlId(r));
    const record = read(found);
    return record && attemptId(record) === attemptId(r) ? record : null;
  }
  function owns(n: Notification, r: Record82) {
    const record = read(n);
    return record !== null && n.data?.role === 'fgs' && attemptId(record) === attemptId(r);
  }
  function background() {
    return deps.isAndroid() && deps.appState.currentState === 'background' && !deps.otherRunning();
  }
  async function arm(
    correlationId: string,
    isCurrent: () => boolean = () => true,
    session?: Session82,
  ) {
    return serial(async () => {
      if (
        !isCurrent() ||
        !deps.isAndroid() ||
        deps.appState.currentState !== 'active' ||
        !validId(correlationId) ||
        deps.otherRunning()
      ) {
        deps.emit('ARM_ADMISSION_REJECTED', {
          correlationId,
          current: isCurrent(),
          android: deps.isAndroid(),
          appState: deps.appState.currentState,
          otherRunning: deps.otherRunning(),
        });
        throw new Error('arm_requires_visible_idle_android');
      }
      if (session && !validSession82(session)) {
        throw new Error('invalid_diagnostic_session');
      }
      const s = await snapshot();
      if (
        !isCurrent() ||
        s.controls.length ||
        s.fgs.length ||
        deps.otherRunning() ||
        deps.appState.currentState !== 'active'
      ) {
        throw new Error('existing_scenario_or_fgs');
      }
      const r: Record82 = {
        correlationId,
        attempt: 1,
        nonce: session ? session.sessionId : deps.nonce(),
        state: 'ready',
        nativeOutstanding: false,
        diagnostic: session
          ? { session, phase: 'open', epoch: '', consumerRunId: '', ledger: [] }
          : null,
      };
      deps.armedChanged?.(true);
      await deps.api.createChannel({
        id: CHANNEL,
        name: 'Issue 82 dataSync diagnostics',
        importance: AndroidImportance.HIGH,
      });
      if (!isCurrent() || deps.appState.currentState !== 'active' || deps.otherRunning()) {
        throw new Error('arm_lost_visibility');
      }
      await deps.api.displayNotification(control(r));
      if (!isCurrent() || deps.appState.currentState !== 'active' || deps.otherRunning()) {
        throw new Error('arm_publication_currentness_lost');
      }
      deps.armedChanged?.(true);
      log('ARMED', r);
    });
  }
  async function handle(
    event: Issue82Event,
    source: 'background' | 'foreground' = 'background',
  ): Promise<boolean> {
    if (event.detail.notification?.data?.smokeScenario !== SCENARIO) {
      return false;
    }
    // Claim all #82 events, including malformed ones, before the generic Alert branch.
    await serial(async () => {
      const r = read(event.detail.notification);
      if (!r) {
        return;
      }
      try {
        const s = await snapshot();
        const c = current(s.controls, r);
        if (!c) {
          log('STALE_EVENT', r);
          return;
        }
        deps.armedChanged?.(true);
        if (c.diagnostic && c.diagnostic.phase !== 'open' && event.type === 9) {
          log('BLOCKED', c, { reason: 'quiescence_blocks_timeout' });
          return;
        }
        if (
          event.type === 9 &&
          event.detail.notification?.data?.role === 'fgs' &&
          ['pending', 'settled'].includes(c.state)
        ) {
          log('FG_TIMEOUT_OBSERVED', c, { eventType: event.type, detail: event.detail });
          release(c);
          // Only prepares the next token. START still refuses displayed FGS; the driver
          // must additionally prove the previous AMS ServiceRecord has disappeared.
          const next = {
            ...c,
            attempt: c.attempt + 1,
            nonce: deps.nonce(),
            state: 'ready' as const,
            diagnostic: c.diagnostic
              ? {
                  ...c.diagnostic,
                  ledger: c.diagnostic.ledger.map(x =>
                    x.attemptId === attemptId(c) ? { ...x, terminal: 'timeout' as const } : x,
                  ),
                }
              : null,
          };
          await deps.api.displayNotification(control(next));
          log('NEXT_ATTEMPT_READY', next, { teardownProven: false });
          return;
        }
        if (
          event.type !== EventType.ACTION_PRESS ||
          event.detail.notification?.data?.role !== 'control'
        ) {
          return;
        }
        const action = event.detail.pressAction?.id;
        if (source !== 'background') {
          log('BLOCKED', c, { reason: 'foreground_handler_action' });
          return;
        }
        log('BACKGROUND_ACTION_RECEIVED', c, { action, source: 'background-handler' });
        if (!background()) {
          log('BLOCKED', c, { reason: 'background_or_scenario_required' });
          return;
        }
        if (action === START) {
          if (
            c.state !== 'ready' ||
            s.fgs.length ||
            (c.diagnostic && c.diagnostic.phase !== 'open')
          ) {
            log('BLOCKED', c, { reason: 'attempt_consumed_or_fgs_present' });
            return;
          }
          const pending = {
            ...c,
            state: 'pending' as const,
            nativeOutstanding: true,
            diagnostic: c.diagnostic
              ? {
                  ...c.diagnostic,
                  ledger: [
                    ...c.diagnostic.ledger,
                    {
                      attemptId: attemptId(c),
                      notificationId: fgsId(c),
                      processRunId,
                      dispatch: 'reserved' as const,
                      terminal: 'none' as const,
                      stopResolved: false,
                    },
                  ],
                }
              : null,
          };
          // Durable consumption precedes dispatch. A cold process cannot replay an old PendingIntent.
          await deps.api.displayNotification(control(pending));
          if (!background()) {
            log('BLOCKED', pending, { reason: 'start_lost_background' });
            return;
          }
          const check = await snapshot();
          if (
            check.fgs.length ||
            !current(check.controls, pending) ||
            !background() ||
            canonical82(current(check.controls, pending)?.diagnostic) !==
              canonical82(pending.diagnostic)
          ) {
            log('BLOCKED', pending, { reason: 'start_preflight_changed' });
            return;
          }
          if (pending.diagnostic) {
            pending.diagnostic.ledger[pending.diagnostic.ledger.length - 1].dispatch = 'started';
            await deps.api.displayNotification(control(pending));
            const final = await snapshot();
            if (
              !background() ||
              final.fgs.length ||
              !current(final.controls, pending) ||
              canonical82(current(final.controls, pending)?.diagnostic) !==
                canonical82(pending.diagnostic)
            ) {
              log('BLOCKED', pending, { reason: 'dispatch_admission_changed' });
              return;
            }
          }
          log('START_REQUESTED', pending);
          let outcome: 'resolved' | 'rejected' = 'resolved';
          try {
            await deps.api.displayNotification(foreground(pending));
            log('START_PROMISE_SETTLED', pending, { outcome: 'resolved', promotionProven: false });
          } catch (error) {
            outcome = 'rejected';
            log('START_PROMISE_SETTLED', pending, {
              outcome: 'rejected',
              promotionProven: false,
              reason: smokeErrorReason(error),
            });
            log('ERROR', pending, {
              stage: 'dispatch_or_display',
              reason: smokeErrorReason(error),
            });
          }
          const settled = await snapshot();
          if (
            current(settled.controls, pending)?.state === 'pending' &&
            canonical82(current(settled.controls, pending)?.diagnostic) ===
              canonical82(pending.diagnostic) &&
            background()
          ) {
            if (pending.diagnostic) {
              pending.diagnostic.ledger[pending.diagnostic.ledger.length - 1].dispatch = outcome;
            }
            await deps.api.displayNotification(control({ ...pending, state: 'settled' }));
          }
          // Do not retry or infer native denial from Promise settlement.
          return;
        }
        if (action !== STOP && action !== CLEANUP) {
          return;
        }
        if (action === CLEANUP && c.diagnostic?.phase === 'quiesced' && event.detail.input) {
          const d = c.diagnostic;
          if (
            d.consumerRunId !== processRunId ||
            d.ledger.length !== c.attempt ||
            d.ledger.some(
              (entry, i) => !entry.attemptId.startsWith(`${c.correlationId}:${i + 1}:`),
            ) ||
            d.ledger[d.ledger.length - 1]?.attemptId !== attemptId(c) ||
            s.fgs.length
          ) {
            log('BLOCKED', c, { reason: 'quiescence_or_ledger_unproven' });
            return;
          }
          const receipt = verifyReceipt82(event.detail.input, d);
          if (
            receipt.correlationId !== c.correlationId ||
            receipt.attemptId !== attemptId(c) ||
            receipt.controlNonce !== c.nonce
          ) {
            log('BLOCKED', c, { reason: 'receipt_owner_mismatch' });
            return;
          }
          const check = await snapshot();
          if (
            !background() ||
            check.fgs.length ||
            canonical82(current(check.controls, c)?.diagnostic) !== canonical82(d)
          ) {
            log('BLOCKED', c, { reason: 'receipt_currentness_lost' });
            return;
          }
          // Persist monouse settlement first; cold restart must reseal under its own processRunId.
          const certified = {
            ...c,
            nativeOutstanding: false,
            diagnostic: { ...d, phase: 'certified' as const },
          };
          await deps.api.displayNotification(control(certified));
          const final = await snapshot();
          if (
            !background() ||
            final.fgs.length ||
            canonical82(current(final.controls, c)?.diagnostic) !==
              canonical82(certified.diagnostic) ||
            receipt.expiresAt <= Date.now()
          ) {
            log('BLOCKED', c, { reason: 'certified_cleanup_currentness_lost' });
            return;
          }
          await deps.api.cancelNotification(controlId(c));
          deps.armedChanged?.(false);
          release(c);
          log(
            'CLEANUP',
            { ...certified, nativeOutstanding: false },
            { certified: true, quiescenceEpoch: d.epoch },
          );
          return;
        }
        if (s.fgs.some(n => !owns(n, c))) {
          log('BLOCKED', c, { reason: 'foreign_fgs' });
          return;
        }
        let stopping = c;
        if (action === STOP && c.diagnostic) {
          const sealed = {
            ...c.diagnostic,
            phase: 'quiesced' as const,
            epoch: deps.nonce(),
            consumerRunId: processRunId,
          };
          stopping = { ...c, diagnostic: sealed };
          await deps.api.displayNotification(control(stopping));
          const check = await snapshot();
          if (
            !background() ||
            canonical82(current(check.controls, c)?.diagnostic) !== canonical82(sealed) ||
            check.fgs.some(n => !owns(n, c))
          ) {
            log('BLOCKED', c, { reason: 'quiescence_currentness_lost' });
            return;
          }
        }
        if (s.fgs.length) {
          const beforeStop = await snapshot();
          if (
            !background() ||
            !current(beforeStop.controls, c) ||
            canonical82(current(beforeStop.controls, c)?.diagnostic) !==
              canonical82(stopping.diagnostic) ||
            !beforeStop.fgs.length ||
            beforeStop.fgs.some(n => !owns(n, c))
          ) {
            log('BLOCKED', c, { reason: 'stop_ownership_changed' });
            return;
          }
          log('STOP_REQUESTED', c);
          await deps.api.stopForegroundService();
          log('STOP_RESOLVED', c, { teardownProven: false });
          if (stopping.diagnostic) {
            const check = await snapshot();
            if (
              !background() ||
              canonical82(current(check.controls, c)?.diagnostic) !==
                canonical82(stopping.diagnostic)
            ) {
              log('BLOCKED', c, { reason: 'stop_continuation_stale' });
              return;
            }
            stopping.diagnostic.ledger = stopping.diagnostic.ledger.map(x =>
              x.attemptId === attemptId(c) ? { ...x, terminal: 'stop', stopResolved: true } : x,
            );
            await deps.api.displayNotification(control(stopping));
          }
          release(c);
        } else if (c.nativeOutstanding && !(action === STOP && stopping.diagnostic)) {
          // No public API proves a pending native acquisition absent. Leave the evidence/control intact.
          log('BLOCKED', c, { reason: 'native_obligations_unproven' });
          return;
        }
        const after = await snapshot();
        if (after.fgs.length || !background() || !current(after.controls, c)) {
          log('BLOCKED', c, { reason: 'stop_teardown_unproven' });
          return;
        }
        if (action === CLEANUP) {
          // Public STOP settlement and notification removal cannot prove native termination.
          // There is deliberately no private bridge for an external AMS observation.
          if (c.nativeOutstanding || current(after.controls, c)?.nativeOutstanding) {
            log('BLOCKED', c, { reason: 'native_obligations_unproven' });
            return;
          }
          await deps.api.cancelNotification(controlId(c));
          deps.armedChanged?.(false);
          release(c);
          log('CLEANUP', c);
        } else {
          await deps.api.displayNotification(control({ ...stopping, state: 'stopped' }));
          if (stopping.diagnostic) {
            const check = await snapshot();
            if (
              background() &&
              canonical82(current(check.controls, c)?.diagnostic) ===
                canonical82(stopping.diagnostic)
            ) {
              log('QUIESCED', stopping, { diagnostic: stopping.diagnostic });
            }
          }
        }
      } catch (error) {
        log('ERROR', r, { reason: smokeErrorReason(error) });
      }
    });
    return true;
  }
  function runner(notification: Notification): Promise<void> | null {
    if (notification.data?.smokeScenario !== SCENARIO) {
      return null;
    }
    const r = read(notification);
    if (!r || notification.data?.role !== 'fgs') {
      return Promise.resolve();
    }
    const existing = runners.get(attemptId(r));
    if (existing) {
      return existing.work;
    }
    let resolve!: () => void;
    const work = new Promise<void>(done => {
      resolve = done;
    });
    runners.set(attemptId(r), { work, resolve });
    void serial(async () => {
      try {
        const s = await snapshot();
        const c = current(s.controls, r);
        if (
          runners.get(attemptId(r))?.work !== work ||
          !c ||
          !['pending', 'settled'].includes(c.state) ||
          (c.diagnostic && c.diagnostic.phase !== 'open') ||
          deps.otherRunning()
        ) {
          log('STALE_RUNNER', r);
          if (runners.get(attemptId(r))?.work === work) {
            runners.delete(attemptId(r));
          }
          resolve();
          return;
        }
        deps.armedChanged?.(true);
        log('RUNNER_STARTED', c);
      } catch (error) {
        if (runners.get(attemptId(r))?.work === work) {
          runners.delete(attemptId(r));
        }
        resolve();
        log('ERROR', r, { stage: 'runner_validation', reason: smokeErrorReason(error) });
      }
    });
    return work;
  }
  async function receiveReceipt(correlation: string, wire: string) {
    if (!validId(correlation) || typeof wire !== 'string' || wire.length > 16000 || !background())
      return false;
    const s = await snapshot();
    const controls = s.controls.filter(
      n => n.data?.role === 'control' && n.data?.correlationId === correlation,
    );
    if (controls.length !== 1 || !background()) return false;
    const record = read(controls[0]);
    if (!record || record.diagnostic?.phase !== 'quiesced' || !record.diagnostic.ledger.length)
      return false;
    // Transport has no authority: the same serialized consumer verifies signature,
    // process lifetime, quiescence, exact ledger and monouse before cancellation.
    await handle({
      type: EventType.ACTION_PRESS,
      detail: { notification: controls[0], pressAction: { id: CLEANUP }, input: wire },
    });
    return true;
  }
  return { arm, handle, runner, receiveReceipt };
}

type Globals82 = typeof globalThis & { __NOTIFEE_ISSUE82_ARMED__?: boolean };
export const issue82Harness = createIssue82Harness({
  api: notifee,
  appState: AppState,
  isAndroid: () => Platform.OS === 'android',
  otherRunning: () =>
    Boolean(
      (
        globalThis as typeof globalThis & {
          __NOTIFEE_ISSUE81_HARNESS__?: { isActive: () => boolean };
          __NOTIFEE_ISSUE44_RUNNING__?: boolean;
        }
      ).__NOTIFEE_ISSUE81_HARNESS__?.isActive() ||
      (globalThis as typeof globalThis & { __NOTIFEE_ISSUE44_RUNNING__?: boolean })
        .__NOTIFEE_ISSUE44_RUNNING__,
    ),
  nonce: () => `${Date.now()}-${Math.random().toString(36).slice(2)}`,
  emit: (marker, payload) => console.log(`ISSUE82:${marker} ${JSON.stringify(payload)}`),
  armedChanged: armed => {
    (globalThis as Globals82).__NOTIFEE_ISSUE82_ARMED__ = armed;
  },
});
