const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

// A dependency-free project exercises the actual Yarn scheduler, with workspace
// names deliberately ordered against the dependency graph. No repository build
// outputs or node_modules are changed by these tests.
function fixture(scripts) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'notify-kit-workspaces-'));
  const write = (name, content) => {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), content);
  };
  const json = (name, value) => write(name, JSON.stringify(value, null, 2));
  json('package.json', {
    name: 'react-native-notify-kit-root',
    private: true,
    packageManager: 'yarn@4.6.0',
    workspaces: ['packages/*'],
    dependencies: { rimraf: 'file:tools/rimraf' },
    scripts: {
      'verify:prepare': scripts.prepare,
      'verify:clean': scripts['build:all:clean'],
      prepare: 'node record.cjs root',
      'build:clean': 'node record.cjs root',
      'build:core:ios': 'node record.cjs ios',
    },
  });
  write('.yarnrc.yml', 'nodeLinker: node-modules\nenableGlobalCache: false\n');
  json('tools/rimraf/package.json', {
    name: 'rimraf',
    version: '0.0.0',
    bin: { rimraf: 'cli.cjs' },
  });
  write(
    'tools/rimraf/cli.cjs',
    '#!/usr/bin/env node\nrequire("node:fs").rmSync(process.argv[2], { recursive: true, force: true });\n',
  );
  const specs = [
    ['a-app', { devDependencies: { 'z-core': 'workspace:*' } }],
    ['b-consumer', { dependencies: { 'a-app': 'workspace:*' } }],
    ['f-independent', {}],
    ['m-no-script', {}],
    ['z-core', {}],
  ];
  for (const [name, dependencies] of specs) {
    json(`packages/${name}/package.json`, {
      name,
      version: '1.0.0',
      private: true,
      ...dependencies,
      scripts:
        name === 'm-no-script'
          ? {}
          : {
              prepare: 'node ../../record.cjs prepare',
              'build:clean': 'node ../../record.cjs clean',
            },
    });
    write(`packages/${name}/source.txt`, name);
  }
  write(
    'record.cjs',
    `const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = __dirname;
const kind = process.argv[2];
const manifest = require(path.join(process.cwd(), 'package.json'));
const name = manifest.name;
const event = phase => fs.appendFileSync(path.join(root, 'events.jsonl'), JSON.stringify({kind, name, phase, pid: process.pid}) + '\\n');
event('start');
assert.notEqual(kind, 'root', 'root workspace must be excluded from recursive lifecycle traversal');
if (process.env.FAIL_WORKSPACE === name) process.exit(42);
if (kind === 'ios') {
  fs.mkdirSync(path.join(root, 'ios/build'), {recursive: true});
  fs.writeFileSync(path.join(root, 'ios/build/core.txt'), 'generated core');
} else if (kind === 'clean') {
  fs.rmSync('output.txt', {force: true});
} else {
  for (const dependency of Object.keys({...manifest.dependencies, ...manifest.devDependencies})) {
    const dependencyPath = path.join(root, 'packages', dependency);
    const output = path.join(dependencyPath, 'output.txt');
    assert.ok(fs.existsSync(output), 'dependency must prepare before consumer: ' + dependency + ' -> ' + name);
    assert.equal(fs.readFileSync(output, 'utf8'), fs.readFileSync(path.join(dependencyPath, 'source.txt'), 'utf8'), 'dependency output must reflect its current source');
  }
  fs.writeFileSync('output.txt', fs.readFileSync('source.txt'));
}
event('finish');
`,
  );
  const run = (args, extraEnv = {}) =>
    spawnSync('yarn', args, {
      cwd: root,
      encoding: 'utf8',
      timeout: 45000,
      env: {
        ...process.env,
        ...extraEnv,
        COREPACK_ENABLE_NETWORK: '0',
        YARN_ENABLE_IMMUTABLE_INSTALLS: 'false',
      },
    });
  const install = run(['install', '--mode=skip-build']);
  assert.equal(install.status, 0, install.stdout + install.stderr);
  return {
    root,
    run,
    write,
    json,
    events: () =>
      fs
        .readFileSync(path.join(root, 'events.jsonl'), 'utf8')
        .trim()
        .split('\n')
        .filter(Boolean)
        .map(line => JSON.parse(line)),
    resetEvents: () => write('events.jsonl', ''),
    cleanup: () => fs.rmSync(root, { recursive: true, force: true }),
  };
}

module.exports = { fixture };
