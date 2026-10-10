// Actual TS harness + receipt verifier with Android I/O replaced. Never runs on a device.
const fs = require('fs'),
  vm = require('vm'),
  readline = require('readline');
const root = process.cwd(),
  ts = require(root + '/node_modules/typescript');
const modules = {};
let tick = Date.now(),
  seq = 0,
  app = { currentState: 'active' },
  db = new Map(),
  marks = [],
  h;
class Clock extends Date {
  static now() {
    tick += 10;
    return tick;
  }
}
function load(name) {
  if (modules[name]) return modules[name].exports;
  const mod = { exports: {} };
  modules[name] = mod;
  vm.runInNewContext(
    ts.transpileModule(fs.readFileSync(root + '/apps/smoke/' + name + '.ts', 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText,
    {
      module: mod,
      exports: mod.exports,
      Date: Clock,
      Math,
      BigInt,
      Promise,
      Map,
      Set,
      console,
      require: n =>
        n === './issue82Receipt'
          ? load('issue82Receipt')
          : n === 'react-native'
            ? { AppState: app, Platform: { OS: 'android' } }
            : n === 'react-native-notify-kit'
              ? {
                  default: {},
                  EventType: { ACTION_PRESS: 2 },
                  AndroidImportance: { HIGH: 4 },
                  AndroidForegroundServiceType: { FOREGROUND_SERVICE_TYPE_DATA_SYNC: 1 },
                }
              : { smokeErrorReason: String },
    },
  );
  return mod.exports;
}
const api = {
  createChannel: async () => '',
  displayNotification: async n => {
    db.set(n.id, n);
    return n.id;
  },
  getDisplayedNotifications: async () => [...db.values()].map(notification => ({ notification })),
  cancelNotification: async id => db.delete(id),
  stopForegroundService: async () => {
    for (const [id, n] of db) {
      if (n.android?.asForegroundService) db.delete(id);
    }
  },
};
function make() {
  h = load('issue82DataSyncBudgetHarness').createIssue82Harness({
    api,
    appState: app,
    isAndroid: () => true,
    otherRunning: () => false,
    nonce: () => `nonce${++seq}`,
    emit: (marker, p) => marks.push({ marker, ...p }),
  });
}
make();
readline.createInterface({ input: process.stdin }).on('line', async line => {
  try {
    const c = JSON.parse(line);
    if (c.op === 'arm') {
      app.currentState = 'active';
      await h.arm(c.correlation, () => true, c.session);
      app.currentState = 'background';
    }
    if (c.op === 'action') {
      const n = [...db.values()].find(n => n.data.role === 'control');
      await h.handle({
        type: 2,
        detail: { notification: n, pressAction: { id: c.action }, input: c.input },
      });
      if (c.action === 'issue82-start') {
        const n = [...db.values()].find(n => n.android?.asForegroundService);
        if (n) h.runner(n);
      }
    }
    if (c.op === 'cold') make();
    await new Promise(r => setImmediate(r));
    process.stdout.write(
      JSON.stringify({ now: Clock.now(), notifications: [...db.values()], markers: marks }) + '\n',
    );
  } catch (e) {
    process.stdout.write(JSON.stringify({ error: String(e) }) + '\n');
  }
});
