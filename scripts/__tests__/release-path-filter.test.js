const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { spawnSync } = require('node:child_process');
const { test } = require('node:test');

const filterFile = path.resolve(__dirname, '../../.releaserc.react-native-path-filter.js');
const parserFile = path.join(__dirname, 'node_modules/git-log-parser/src/index.js');

test('actual semantic-release parser handles absent historical paths in a clean checkout', () => {
  const result = spawnSync(
    process.execPath,
    [path.join(__dirname, 'helpers/release-filter-actual.cjs')],
    { encoding: 'utf8' },
  );
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('semantic-release recognizes the existing public v-prefixed release history', () => {
  const config = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../.releaserc'), 'utf8'));
  // These are published repository tags, not new fixture tags or publication actions.
  for (const [version, existingTag] of [
    ['10.8.0', 'v10.8.0'],
    ['10.8.1', 'v10.8.1'],
  ]) {
    assert.equal(
      config.tagFormat.replace('${version}', version),
      existingTag,
      'A different tag namespace makes semantic-release treat this package as unreleased',
    );
  }
});

function filtered(initial) {
  let received;
  require.cache[parserFile] = {
    exports: {
      parse: (config, options) => {
        received = { config, options };
        return 'parsed';
      },
    },
  };
  delete require.cache[filterFile];
  try {
    require(filterFile);
    assert.equal(require.cache[parserFile].exports.parse(initial, { marker: true }), 'parsed');
    return received;
  } finally {
    delete require.cache[parserFile];
    delete require.cache[filterFile];
  }
}

test('semantic-release sees CLI and packaging source changes that affect the public tarball', () => {
  const { config, options } = filtered({});
  for (const affected of [
    'ios',
    'android',
    'packages/react-native',
    'packages/cli',
    'build_ios_core.sh',
    'scripts/prepack-cli.sh',
    'scripts/cli-artifacts.cjs',
  ]) {
    assert.ok(config._.includes(affected), `release filter omits ${affected}`);
  }
  assert.deepEqual(options, { marker: true });
});

test('release filtering preserves pre-existing raw git arguments', () => {
  assert.equal(filtered({ _: '--first-parent' }).config._[0], '--first-parent');
  assert.deepEqual(filtered({ _: ['--first-parent', '--no-merges'] }).config._.slice(0, 2), [
    '--first-parent',
    '--no-merges',
  ]);
});
