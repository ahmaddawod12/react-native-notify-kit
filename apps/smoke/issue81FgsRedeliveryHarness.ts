import { AppState, PermissionsAndroid, Platform } from 'react-native';
import notifee, { AndroidForegroundServiceType, AndroidImportance } from 'react-native-notify-kit';
import type { Notification } from 'react-native-notify-kit';
import { smokeErrorReason } from './smokeAutomation';

const PREFIX = 'notifykit://issue81/fgs';
const NOTIFICATION_ID = 'issue81-fgs';
const CHANNEL_ID = 'issue81_fgs';
type Runner = (notification: Notification) => Promise<void>;
type Api = Pick<
  typeof notifee,
  | 'createChannel'
  | 'displayNotification'
  | 'stopForegroundService'
  | 'getDisplayedNotifications'
  | 'registerForegroundService'
>;

export type Issue81Request = {
  action: 'start' | 'stop' | 'status';
  correlationId: string;
  type: 'dataSync' | 'microphone';
  intervalMs: number;
  maxUpdates: number;
  safetyTimeoutMs: number;
  error?: string;
};

export function extractIssue81DeepLink(url: string): Issue81Request | null {
  if (url.split(/[?#]/)[0] !== PREFIX) {
    return null;
  }
  const request: Issue81Request = {
    action: 'start',
    correlationId: '',
    type: 'dataSync',
    intervalMs: 2000,
    maxUpdates: 60,
    safetyTimeoutMs: 120000,
  };
  try {
    const query: Record<string, string> = {};
    for (const part of (url.split('?')[1] ?? '').split('#')[0].split('&').filter(Boolean)) {
      const index = part.indexOf('=');
      const key = decodeURIComponent(index < 0 ? part : part.slice(0, index));
      if (Object.prototype.hasOwnProperty.call(query, key)) {
        throw new Error('duplicate_parameter');
      }
      query[key] = decodeURIComponent(index < 0 ? '' : part.slice(index + 1));
    }
    request.correlationId = query.correlationId ?? '';
    if (!/^[a-zA-Z0-9_-]{1,80}$/.test(request.correlationId)) {
      throw new Error('invalid_correlation_id');
    }
    if (query.action != null && !['start', 'stop', 'status'].includes(query.action)) {
      throw new Error('invalid_action');
    }
    request.action = (query.action ?? 'start') as Issue81Request['action'];
    if (query.type != null && !['dataSync', 'microphone'].includes(query.type)) {
      throw new Error('unsupported_type');
    }
    request.type = (query.type ?? 'dataSync') as Issue81Request['type'];
    for (const [key, minimum, maximum] of [
      ['intervalMs', 100, 60000],
      ['maxUpdates', 1, 10000],
      ['safetyTimeoutMs', 1000, 600000],
    ] as const) {
      if (query[key] == null) {
        continue;
      }
      const value = Number(query[key]);
      if (
        !/^\d+$/.test(query[key]) ||
        !Number.isSafeInteger(value) ||
        value < minimum ||
        value > maximum
      ) {
        throw new Error(`invalid_${key}`);
      }
      request[key] = value;
    }
  } catch (error) {
    request.error = smokeErrorReason(error);
  }
  return request;
}

// Smoke-only router, installed when App.tsx is imported, before index.js registers
// its default runner. Keep the latest caller's runner for every other notification.
// No private NotifyKit fields, headless keys or native hooks are used.
export function createIssue81Harness(deps: {
  api: Api;
  appState: {
    readonly currentState: string | null;
    addEventListener: (
      event: 'change',
      listener: (state: string) => void,
    ) => { remove: () => void };
  };
  isAndroid: () => boolean;
  otherScenarioRunning: () => boolean;
  emit: (marker: string, data: Record<string, unknown>) => void;
  permissionGranted?: (type: Issue81Request['type']) => Promise<boolean>;
}) {
  type Session = {
    request: Issue81Request;
    ownershipRevision: number;
    completed: number;
    requested: number;
    stopping: boolean;
    touched: boolean;
    halted: boolean;
    work?: Promise<void>;
    stopWork?: Promise<void>;
    timer?: ReturnType<typeof setTimeout>;
    safety?: ReturnType<typeof setTimeout>;
    foregroundTimer?: ReturnType<typeof setTimeout>;
    resolveForeground?: () => void;
    subscription?: { remove: () => void };
    deadline: number;
    due: number;
    runner: Promise<void>;
    resolveRunner: () => void;
  };
  let active: Session | undefined;
  let coldStopPending = false;
  let ownershipRevision = 0;
  const revokeOwnership = () => {
    ++ownershipRevision;
    if (active) {
      active.halted = true;
      active.touched = false;
      clearTimeout(active.timer);
    }
  };
  let fallback: Runner | undefined;
  const usedCorrelations = new Set<string>();
  const processRunId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const log = (marker: string, request: Issue81Request, extra: Record<string, unknown> = {}) =>
    deps.emit(marker, {
      scenario: 'issue81-fgs',
      processRunId,
      correlationId: request.correlationId,
      loggedAt: Date.now(),
      appState: deps.appState.currentState,
      notificationId: NOTIFICATION_ID,
      ...extra,
    });
  const stateLog = (marker: string, s: Session, extra: Record<string, unknown> = {}) =>
    log(marker, s.request, {
      requested: s.requested,
      completed: s.completed,
      stopping: s.stopping,
      ...extra,
    });
  const originalRegister = deps.api.registerForegroundService.bind(deps.api);
  deps.api.registerForegroundService = runner => {
    // Preserve the public method's validation for malformed callers too.
    if (typeof runner !== 'function') {
      return originalRegister(runner);
    }
    revokeOwnership();
    fallback = runner;
    originalRegister(notification => {
      const s = active;
      if (
        notification.id !== NOTIFICATION_ID ||
        notification.data?.smokeScenario !== 'issue81-fgs'
      ) {
        revokeOwnership();
        if (s) {
          stateLog('INTERFERENCE', s, { foreignNotificationId: notification.id });
        }
        return fallback?.(notification) ?? Promise.resolve();
      }
      if (!s || notification.data?.correlationId !== s.request.correlationId) {
        revokeOwnership();
        deps.emit('ORPHAN_RUNNER', {
          scenario: 'issue81-fgs',
          processRunId,
          loggedAt: Date.now(),
          correlationId: notification.data?.correlationId,
          notificationId: notification.id,
        });
        return Promise.resolve();
      }
      stateLog('RUNNER_STARTED', s);
      return s.runner;
    });
  };

  function makeSession(request: Issue81Request): Session {
    let resolveRunner!: () => void;
    const runner = new Promise<void>(resolve => {
      resolveRunner = resolve;
    });
    return {
      request,
      ownershipRevision,
      requested: 0,
      completed: 0,
      stopping: false,
      touched: false,
      halted: false,
      deadline: Date.now() + request.safetyTimeoutMs,
      due: 0,
      runner,
      resolveRunner,
    };
  }
  function buildNotification(s: Session, sequence: number): Notification {
    return {
      id: NOTIFICATION_ID,
      title: 'Issue 81 persistent FGS',
      body: `issue81:${s.request.correlationId}:update:${sequence}`,
      data: {
        smokeScenario: 'issue81-fgs',
        correlationId: s.request.correlationId,
        sequence: String(sequence),
      },
      android: {
        channelId: CHANNEL_ID,
        ongoing: true,
        onlyAlertOnce: true,
        asForegroundService: true,
        foregroundServiceTypes: [
          s.request.type === 'dataSync'
            ? AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            : AndroidForegroundServiceType.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        ],
      },
    };
  }
  async function stop(s: Session, reason: string): Promise<void> {
    if (s.stopWork) {
      await s.stopWork;
      return;
    }
    s.stopping = true;
    clearTimeout(s.timer);
    clearTimeout(s.safety);
    clearTimeout(s.foregroundTimer);
    s.resolveForeground?.();
    stateLog('STOP_REQUESTED', s, { reason, pending: s.work != null });
    s.stopWork = (async () => {
      // Native display cannot be cancelled. Drain it before STOP and do not admit
      // another session until cleanup succeeds; late settlement cannot promote B.
      await s.work;
      try {
        const otherRunning = deps.otherScenarioRunning();
        const nativeStopAttempted =
          !otherRunning && active === s && s.touched && s.ownershipRevision === ownershipRevision;
        if (nativeStopAttempted) {
          await deps.api.stopForegroundService();
        }
        stateLog('STOP_RESOLVED', s, { reason, nativeStopAttempted });
        s.subscription?.remove();
        s.resolveRunner();
        stateLog('RUNNER_RESOLVED', s);
        if (active === s) {
          active = undefined;
        }
      } catch (error) {
        stateLog('STOP_FAILED', s, { reason: smokeErrorReason(error) });
      }
    })();
    await s.stopWork;
    s.stopWork = undefined;
  }
  function schedule(s: Session) {
    if (active !== s || s.stopping || s.halted) {
      return;
    }
    if (s.requested >= s.request.maxUpdates) {
      stateLog('UPDATE_LIMIT', s);
      return;
    }
    s.due = Date.now() + s.request.intervalMs;
    s.timer = setTimeout(() => {
      if (active !== s || s.stopping) {
        return;
      }
      const latenessMs = Date.now() - s.due;
      if (latenessMs > s.request.intervalMs) {
        stateLog('TIMER_DELAYED', s, { latenessMs });
      }
      if (Date.now() >= s.deadline) {
        stateLog('SAFETY_TIMEOUT', s);
        void stop(s, 'safety_timeout');
        return;
      }
      s.work = (async () => {
        const sequence = ++s.requested;
        const requestedAppState = deps.appState.currentState;
        stateLog('UPDATE_REQUESTED', s, { sequence });
        try {
          await deps.api.displayNotification(buildNotification(s, sequence));
          ++s.completed;
          stateLog('UPDATE_RESOLVED', s, { sequence, requestedAppState });
        } catch (error) {
          s.halted = true;
          stateLog('UPDATE_FAILED', s, { sequence, reason: smokeErrorReason(error) });
        }
      })();
      void s.work.then(() => {
        s.work = undefined;
        schedule(s);
      });
    }, s.request.intervalMs);
  }
  async function execute(request: Issue81Request): Promise<void> {
    if (request.error) {
      log('BLOCKED', request, { reason: request.error });
      return;
    }
    if (!deps.isAndroid()) {
      log('BLOCKED', request, { reason: 'android_only' });
      return;
    }
    if (request.action === 'status') {
      if (active) {
        stateLog('STATUS', active);
      } else {
        log('NO_SESSION', request);
      }
      return;
    }
    if (request.action === 'stop') {
      if (!active) {
        if (coldStopPending) {
          log('STOP_PENDING', request);
          return;
        }
        coldStopPending = true;
        const admissionRevision = ownershipRevision;
        log('RESTART_LOOKUP', request);
        try {
          // On a fresh process, adopt only our displayed notification with this
          // correlation. Do not stop a singleton service owned by another smoke.
          const displayed = await deps.api.getDisplayedNotifications();
          const ownsNotification = (notification: Notification) =>
            notification.id === NOTIFICATION_ID &&
            notification.data?.correlationId === request.correlationId &&
            notification.data?.smokeScenario === 'issue81-fgs' &&
            notification.android?.asForegroundService === true;
          const owned = displayed.some(item => ownsNotification(item.notification));
          const foreign = displayed.some(
            item =>
              item.notification.android?.asForegroundService &&
              !ownsNotification(item.notification),
          );
          const otherRunning = deps.otherScenarioRunning();
          if (
            !owned ||
            foreign ||
            otherRunning ||
            active ||
            admissionRevision !== ownershipRevision
          ) {
            log('NO_SESSION', request);
            return;
          }
          active = makeSession(request);
          active.touched = true;
          log('RESTART_CLEANUP', request);
          await stop(active, 'explicit');
          return;
        } finally {
          coldStopPending = false;
        }
      }
      if (active.request.correlationId !== request.correlationId) {
        log('BLOCKED', request, {
          reason: 'stale_stop',
          activeCorrelationId: active.request.correlationId,
        });
        return;
      }
      await stop(active, 'explicit');
      return;
    }
    if (active || coldStopPending || deps.otherScenarioRunning()) {
      if (active && !active.stopping && active.request.correlationId === request.correlationId) {
        stateLog('START_DUPLICATE', active);
        return;
      }
      log('BLOCKED', request, {
        reason: 'scenario_already_active',
        activeCorrelationId: active?.request.correlationId,
      });
      return;
    }
    if (usedCorrelations.has(request.correlationId)) {
      log('BLOCKED', request, { reason: 'correlation_already_used' });
      return;
    }
    const s = makeSession(request);
    usedCorrelations.add(request.correlationId);
    active = s; // Reserve before any async permission/storage/native operation.
    stateLog('START_REQUESTED', s, { config: request });
    s.safety = setTimeout(() => {
      stateLog('SAFETY_TIMEOUT', s);
      void stop(s, 'safety_timeout');
    }, request.safetyTimeoutMs);
    s.subscription = deps.appState.addEventListener('change', nextState => {
      stateLog(nextState === 'background' ? 'BACKGROUND' : 'APP_STATE', s, { nextState });
      if (nextState === 'active') {
        clearTimeout(s.foregroundTimer);
        s.resolveForeground?.();
      }
    });
    s.work = (async () => {
      try {
        if (deps.appState.currentState !== 'active') {
          stateLog('WAITING_FOR_FOREGROUND', s);
          await new Promise<void>(resolve => {
            s.resolveForeground = resolve;
            s.foregroundTimer = setTimeout(resolve, 5000);
          });
          s.resolveForeground = undefined;
          if (s.stopping || s.halted) {
            return;
          }
          if (deps.appState.currentState !== 'active') {
            throw new Error('foreground_timeout');
          }
          stateLog('FOREGROUND_ADMITTED', s);
        }
        if (deps.permissionGranted && !(await deps.permissionGranted(request.type))) {
          throw new Error('permission_missing');
        }
        const displayed = await deps.api.getDisplayedNotifications();
        if (displayed.some(item => item.notification.android?.asForegroundService)) {
          throw new Error('existing_fgs_notification');
        }
        if (s.stopping || s.halted) {
          return;
        }
        if (Date.now() >= s.deadline) {
          throw new Error('startup_safety_timeout');
        }
        if (deps.appState.currentState !== 'active') {
          throw new Error('start_lost_foreground');
        }
        await deps.api.createChannel({
          id: CHANNEL_ID,
          name: 'Issue 81 FGS',
          importance: AndroidImportance.LOW,
        });
        if (s.stopping || s.halted) {
          return;
        }
        if (Date.now() >= s.deadline) {
          throw new Error('startup_safety_timeout');
        }
        if (deps.appState.currentState !== 'active') {
          throw new Error('start_lost_foreground');
        }
        s.touched = true;
        await deps.api.displayNotification(buildNotification(s, 0));
        if (Date.now() >= s.deadline) {
          s.halted = true;
          stateLog('SAFETY_TIMEOUT', s);
        }
        if (!s.stopping && !s.halted) {
          stateLog('READY', s, { config: request });
        }
      } catch (error) {
        s.halted = true;
        const reason = smokeErrorReason(error);
        stateLog(
          [
            'foreground_timeout',
            'startup_safety_timeout',
            'start_lost_foreground',
            'permission_missing',
            'existing_fgs_notification',
          ].includes(reason)
            ? 'BLOCKED'
            : 'START_FAILED',
          s,
          { reason },
        );
      }
    })();
    await s.work;
    s.work = undefined;
    if (s.halted && !s.stopping) {
      await stop(s, 'start_failed');
    } else {
      schedule(s);
    }
  }
  return { execute, isActive: () => active != null || coldStopPending };
}

type Issue81Globals = typeof globalThis & {
  __NOTIFEE_ISSUE81_HARNESS__?: ReturnType<typeof createIssue81Harness>;
};
const globals = globalThis as Issue81Globals;
export function installIssue81SmokeRouter() {
  if (!globals.__NOTIFEE_ISSUE81_HARNESS__) {
    globals.__NOTIFEE_ISSUE81_HARNESS__ = createIssue81Harness({
      api: notifee,
      appState: AppState,
      isAndroid: () => Platform.OS === 'android',
      otherScenarioRunning: () =>
        Boolean(
          (globalThis as typeof globalThis & { __NOTIFEE_ISSUE44_RUNNING__?: boolean })
            .__NOTIFEE_ISSUE44_RUNNING__,
        ),
      emit: (marker, payload) => console.log(`ISSUE81:${marker} ${JSON.stringify(payload)}`),
      permissionGranted: async type => {
        if (
          Number(Platform.Version) >= 33 &&
          !(await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS))
        ) {
          return false;
        }
        return (
          type !== 'microphone' ||
          PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.RECORD_AUDIO)
        );
      },
    });
  }
  return globals.__NOTIFEE_ISSUE81_HARNESS__;
}
