const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');

const root = path.resolve(__dirname, '../../..');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'notifykit-release-filter-'));
function git(...args) {
  return execFileSync('git', args, {
    cwd: scratch,
    encoding: 'utf8',
    env: { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' },
  }).trim();
}
function write(name, content) {
  const file = path.join(scratch, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content);
}

(async () => {
  try {
    // This disposable fixture is outside the product repository and has no remote.
    git('init', '-q', '-b', 'main');
    git('config', 'user.name', 'Release Test');
    git('config', 'user.email', 'release-test@example.invalid');
    git('config', 'commit.gpgsign', 'false');
    const initial = [
      'ios/.keep',
      'build_ios_core.sh',
      'packages/react-native/.keep',
      'packages/cli/.keep',
      'scripts/prepack-cli.sh',
      'scripts/cli-artifacts.cjs',
      'README.md',
    ];
    for (const name of initial) write(name, 'baseline');
    git('add', '--', ...initial);
    git('commit', '-q', '-m', 'chore: fixture baseline');
    const baseline = git('rev-parse', 'HEAD');
    for (const [name, message] of [
      ['README.md', 'docs: excluded'],
      ['packages/react-native/index.js', 'feat: product'],
      ['packages/cli/src/cli.ts', 'fix: CLI'],
      ['scripts/prepack-cli.sh', 'fix: prepack'],
      ['scripts/cli-artifacts.cjs', 'fix: staging'],
    ]) {
      write(name, message);
      git('add', '--', name);
      git('commit', '-q', '-m', message);
    }
    assert.equal(fs.existsSync(path.join(scratch, 'android')), false);
    const releaseDir = path.dirname(require.resolve('semantic-release'));
    const getCommits = (await import(pathToFileURL(path.join(releaseDir, 'lib/get-commits.js'))))
      .default;
    require(path.join(root, '.releaserc.react-native-path-filter.js'));
    const actual = (
      await getCommits({
        cwd: scratch,
        env: {},
        lastRelease: { gitHead: baseline },
        nextRelease: { gitHead: 'HEAD' },
        logger: { log() {} },
      })
    ).map(commit => commit.message);
    assert.deepEqual(
      actual,
      ['fix: staging', 'fix: prepack', 'fix: CLI', 'feat: product'],
      'Actual release parser must retain product commits when historical paths do not exist',
    );
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
