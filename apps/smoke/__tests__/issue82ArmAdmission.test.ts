import { createIssue82ArmAdmission } from '../issue82ArmAdmission';

test('warm URL waits for real active AppState before single ARM admission', () => {
  const appState = { currentState: 'background' };
  const arm = jest.fn(async (_id: string) => undefined);
  const q = createIssue82ArmAdmission({ appState, arm });
  q.request('warm', undefined);
  expect(arm).not.toHaveBeenCalled();
  appState.currentState = 'active';
  q.changed();
  q.changed();
  expect(arm).toHaveBeenCalledTimes(1);
  expect(arm.mock.calls[0][0]).toBe('warm');
});

test('disposed effect and superseded URI revoke immutable admission origin', () => {
  const appState = { currentState: 'active' };
  const checks: Array<() => boolean> = [];
  const q = createIssue82ArmAdmission({
    appState,
    arm: async (_id, current) => {
      checks.push(current);
    },
  });
  q.request('old', undefined);
  expect(checks[0]()).toBe(true);
  q.request('new', undefined);
  expect(checks[0]()).toBe(false);
  expect(checks[1]()).toBe(true);
  q.dispose();
  expect(checks[1]()).toBe(false);
  q.request('later', undefined);
  q.changed();
  expect(checks).toHaveLength(2);
});

test('background queued intent cannot survive effect disposal', () => {
  const appState = { currentState: 'background' };
  const arm = jest.fn(async (_id: string) => undefined);
  const q = createIssue82ArmAdmission({ appState, arm });
  q.request('stale', undefined);
  q.dispose();
  appState.currentState = 'active';
  q.changed();
  expect(arm).not.toHaveBeenCalled();
});
