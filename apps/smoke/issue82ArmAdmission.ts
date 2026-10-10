import type { Session82 } from './issue82Receipt';

export function createIssue82ArmAdmission(deps: {
  appState: { readonly currentState: string | null };
  arm: (id: string, current: () => boolean, session?: Session82) => Promise<unknown>;
  rejected?: (id: string, error: unknown) => void;
}) {
  let live = true;
  let generation = 0;
  let pending: { id: string; session?: Session82; origin: number } | null = null;
  const changed = () => {
    if (!live || !pending || deps.appState.currentState !== 'active') return;
    const request = pending;
    pending = null;
    void deps
      .arm(request.id, () => live && generation === request.origin, request.session)
      .catch(error => {
        if (live && generation === request.origin) deps.rejected?.(request.id, error);
      });
  };
  return {
    request(id: string, session?: Session82) {
      if (!live) return;
      pending = { id, session, origin: ++generation };
      changed();
    },
    changed,
    dispose() {
      live = false;
      ++generation;
      pending = null;
    },
  };
}
