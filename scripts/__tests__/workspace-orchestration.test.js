const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { fixture } = require('./helpers/workspace-orchestration');
const manifest = require('../../package.json');

function success(result) {
  assert.equal(result.status, 0, result.stdout + result.stderr);
}

function prepared(events) {
  return events.filter(event => event.kind === 'prepare' && event.phase === 'finish');
}

function assertOrder(events) {
  const finished = prepared(events).map(event => event.name);
  assert.deepEqual([...finished].sort(), ['a-app', 'b-consumer', 'f-independent', 'z-core']);
  assert.ok(finished.indexOf('z-core') < finished.indexOf('a-app'));
  assert.ok(finished.indexOf('a-app') < finished.indexOf('b-consumer'));
  assert.ok(events.every(event => event.kind !== 'root'));
  assert.equal(events.at(-1).kind, 'ios');
  assert.equal(events.at(-1).phase, 'finish');
}

test('workspace builds use Yarn without retaining the legacy scheduler', () => {
  assert.equal(manifest.devDependencies.lerna, undefined);
  assert.equal(manifest.resolutions['nx/smol-toml'], undefined);
  assert.equal(fs.existsSync(path.join(__dirname, '../../lerna.json')), false);
});

test('prepare covers private workspaces, orders regular and dev edges, and excludes root', () => {
  const project = fixture(manifest.scripts);
  try {
    success(project.run(['run', 'verify:prepare']));
    assertOrder(project.events());
  } finally {
    project.cleanup();
  }
});

test('incremental prepare refreshes changed sources and is idempotent', () => {
  const project = fixture(manifest.scripts);
  try {
    success(project.run(['run', 'verify:prepare']));
    project.write('packages/z-core/source.txt', 'updated core');
    project.resetEvents();
    success(project.run(['run', 'verify:prepare']));
    assertOrder(project.events());
    const output = fs.readFileSync(path.join(project.root, 'packages/z-core/output.txt'), 'utf8');
    assert.equal(output, 'updated core');
    project.resetEvents();
    success(project.run(['run', 'verify:prepare']));
    assertOrder(project.events());
    assert.equal(
      fs.readFileSync(path.join(project.root, 'packages/z-core/output.txt'), 'utf8'),
      output,
    );
  } finally {
    project.cleanup();
  }
});

test('clean removes root and package build outputs, preserves source, and can be repeated', () => {
  const project = fixture(manifest.scripts);
  try {
    success(project.run(['run', 'verify:prepare']));
    project.resetEvents();
    success(project.run(['run', 'verify:clean']));
    assert.equal(fs.existsSync(path.join(project.root, 'ios/build')), false);
    const events = project.events();
    const cleaned = events.filter(event => event.kind === 'clean' && event.phase === 'finish');
    assert.deepEqual(cleaned.map(event => event.name).sort(), [
      'a-app',
      'b-consumer',
      'f-independent',
      'z-core',
    ]);
    assert.ok(events.every(event => event.kind !== 'root'));
    assert.ok(
      cleaned.findIndex(event => event.name === 'z-core') <
        cleaned.findIndex(event => event.name === 'a-app'),
    );
    for (const event of cleaned) {
      assert.equal(
        fs.existsSync(path.join(project.root, `packages/${event.name}/output.txt`)),
        false,
      );
      assert.equal(
        fs.readFileSync(path.join(project.root, `packages/${event.name}/source.txt`), 'utf8'),
        event.name,
      );
    }
    success(project.run(['run', 'verify:clean']));
    project.resetEvents();
    success(project.run(['run', 'verify:prepare']));
    assertOrder(project.events());
  } finally {
    project.cleanup();
  }
});

test('a failed prerequisite is nonzero and blocks dependents and iOS generation', () => {
  const project = fixture(manifest.scripts);
  try {
    const result = project.run(['run', 'verify:prepare'], { FAIL_WORKSPACE: 'z-core' });
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.error, undefined);
    const events = project.events();
    assert.ok(events.some(event => event.name === 'z-core' && event.phase === 'start'));
    assert.ok(events.every(event => !['a-app', 'b-consumer'].includes(event.name)));
    assert.ok(events.every(event => event.kind !== 'ios'));
    assert.equal(fs.existsSync(path.join(project.root, 'packages/z-core/output.txt')), false);
  } finally {
    project.cleanup();
  }
});

test('a failed clean blocks dependent workspace cleaning and returns nonzero', () => {
  const project = fixture(manifest.scripts);
  try {
    success(project.run(['run', 'verify:prepare']));
    project.resetEvents();
    const result = project.run(['run', 'verify:clean'], { FAIL_WORKSPACE: 'z-core' });
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.error, undefined);
    const events = project.events();
    assert.ok(events.some(event => event.name === 'z-core' && event.phase === 'start'));
    assert.ok(events.every(event => !['a-app', 'b-consumer'].includes(event.name)));
    for (const name of ['z-core', 'a-app', 'b-consumer']) {
      assert.equal(fs.existsSync(path.join(project.root, `packages/${name}/output.txt`)), true);
    }
  } finally {
    project.cleanup();
  }
});

test('cyclic workspace build dependencies fail instead of running in arbitrary order', () => {
  const project = fixture(manifest.scripts);
  try {
    const location = 'packages/z-core/package.json';
    const core = JSON.parse(fs.readFileSync(path.join(project.root, location), 'utf8'));
    core.dependencies = { 'b-consumer': 'workspace:*' };
    project.json(location, core);
    success(project.run(['install', '--mode=skip-build']));
    const result = project.run(['run', 'verify:prepare']);
    assert.notEqual(result.status, 0, result.stdout + result.stderr);
    assert.equal(result.error, undefined);
    assert.match(result.stdout + result.stderr, /cyclic|cycle/i);
  } finally {
    project.cleanup();
  }
});
