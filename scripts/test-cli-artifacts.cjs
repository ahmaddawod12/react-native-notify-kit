#!/usr/bin/env node
'use strict';

// Mutations and pack lifecycle execute only in this external scratch fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { inventory } = require('./cli-artifacts.cjs');

const source = path.resolve(__dirname, '..');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'notify-kit-cli-pack-'));
const cliDir = path.join(scratch, 'packages/cli');
const rnDir = path.join(scratch, 'packages/react-native');
const target = path.join(rnDir, 'cli');
const script = path.join(scratch, 'scripts/cli-artifacts.cjs');
const transaction = path.join(scratch, 'packages/.cli-pack-transaction');

function run(mode, expected = 0) {
  const result = spawnSync(process.execPath, [script, mode], { encoding: 'utf8' });
  assert.equal(result.status, expected, result.stdout + result.stderr);
  return result;
}

function pack() {
  const result = JSON.parse(
    execFileSync('npm', ['pack', '--ignore-scripts', '--json'], {
      cwd: rnDir,
      encoding: 'utf8',
    }),
  )[0];
  const bytes = fs.readFileSync(path.join(rnDir, result.filename));
  fs.unlinkSync(path.join(rnDir, result.filename));
  return { bytes, files: result.files.map(file => file.path) };
}

try {
  fs.mkdirSync(path.join(scratch, 'scripts'), { recursive: true });
  fs.copyFileSync(path.join(source, 'scripts/cli-artifacts.cjs'), script);
  fs.cpSync(path.join(source, 'packages/cli'), cliDir, {
    recursive: true,
    filter: filename => !['node_modules', 'dist'].includes(path.basename(filename)),
  });
  fs.symlinkSync(path.join(source, 'packages/cli/node_modules'), path.join(cliDir, 'node_modules'));
  fs.mkdirSync(rnDir);
  fs.writeFileSync(
    path.join(rnDir, 'package.json'),
    JSON.stringify({
      name: 'notify-kit-cli-artifact-fixture',
      version: '10.9.0',
      files: ['cli'],
    }),
  );

  run('prepack');
  const clean = inventory(target);
  const cleanPack = pack();
  assert(!cleanPack.files.some(filename => /cli\.bundle|\.map$|__tests__/.test(filename)));
  assert(cleanPack.files.includes('cli/vendor/xcode/LICENSE'));
  assert(cleanPack.files.includes('cli/vendor/xcode/NOTICE'));
  assert(cleanPack.files.includes('cli/vendor/xcode/lib/parseJob.js'));
  assert.equal(clean['bin/react-native-notify-kit'].mode, 0o755);
  assert.equal(JSON.parse(fs.readFileSync(path.join(target, 'package.json'))).version, '10.9.0');
  run('postpack');
  assert(!fs.existsSync(target));
  run('postpack'); // no-op after completion

  // Old ignored output and a pre-existing user directory cannot contaminate a tarball.
  fs.mkdirSync(path.join(cliDir, 'dist'), { recursive: true });
  fs.writeFileSync(path.join(cliDir, 'dist/cli.bundle.js'), 'STALE EMBEDDED DEPENDENCY');
  fs.writeFileSync(path.join(cliDir, 'dist/user-file.txt'), 'user output must survive');
  fs.mkdirSync(target);
  fs.writeFileSync(path.join(target, 'user-file.txt'), 'keep exact original bytes\0');
  fs.mkdirSync(path.join(target, 'empty-user-directory'));
  const original = inventory(target);
  run('prepack');
  assert.deepEqual(inventory(target), clean, 'stale build output changed the CLI artifact');
  assert.deepEqual(pack().bytes, cleanPack.bytes, 'clean/stale npm tarballs differ');
  assert.equal(run('prepack', 1).stderr.includes('transaction already exists'), true);
  assert.deepEqual(inventory(path.join(transaction, 'original')), original);
  run('postpack');
  assert(fs.existsSync(target), 'postpack lost the original user directory');
  assert.deepEqual(inventory(target), original, 'postpack lost original user files');
  assert.equal(
    fs.readFileSync(path.join(cliDir, 'dist/user-file.txt'), 'utf8'),
    'user output must survive',
  );

  // A user's write during packing must be kept, and no transaction may overwrite it.
  run('prepack');
  fs.writeFileSync(path.join(target, 'user-write-during-pack'), 'keep me');
  assert.equal(run('postpack', 1).stderr.includes('refusing cleanup'), true);
  assert.equal(fs.readFileSync(path.join(target, 'user-write-during-pack'), 'utf8'), 'keep me');
  assert.deepEqual(inventory(path.join(transaction, 'original')), original);
  fs.unlinkSync(path.join(target, 'user-write-during-pack'));
  run('postpack');
  assert.deepEqual(inventory(target), original);

  // Renaming a relative symlink changes where it resolves. Preserve the entry
  // itself even when the backup or the original target is a dangling link.
  const savedOriginal = path.join(rnDir, 'saved-original-cli');
  const userDirectory = path.join(rnDir, 'user-cli');
  fs.renameSync(target, savedOriginal);
  fs.mkdirSync(userDirectory);
  fs.writeFileSync(path.join(userDirectory, 'sentinel'), 'USER OWNED BYTES\0');
  const originalUserFiles = inventory(userDirectory);
  for (const link of ['user-cli', 'missing-user-cli']) {
    fs.symlinkSync(link, target);
    const originalLinkBytes = fs.readlinkSync(target, { encoding: 'buffer' });
    const originallyResolves = fs.existsSync(target);
    run('prepack');
    assert.deepEqual(inventory(target), clean, 'symlink original contaminated artifacts');
    assert(fs.lstatSync(path.join(transaction, 'original')).isSymbolicLink());
    assert.deepEqual(
      fs.readlinkSync(path.join(transaction, 'original'), {
        encoding: 'buffer',
      }),
      originalLinkBytes,
      'prepack changed symlink bytes',
    );
    run('postpack');
    assert(fs.lstatSync(target).isSymbolicLink(), 'postpack lost original symlink');
    assert.deepEqual(
      fs.readlinkSync(target, { encoding: 'buffer' }),
      originalLinkBytes,
      'postpack changed symlink bytes',
    );
    assert.equal(
      fs.existsSync(target),
      originallyResolves,
      'postpack changed original symlink resolution',
    );
    assert.deepEqual(
      inventory(userDirectory),
      originalUserFiles,
      'packing modified the user-owned symlink destination',
    );
    assert(!fs.existsSync(transaction), 'symlink restoration left a live transaction');
    fs.unlinkSync(target);
  }
  fs.renameSync(savedOriginal, target);

  // A failed compile cannot change or delete the target or leave a live transaction.
  fs.appendFileSync(path.join(cliDir, 'src/cli.ts'), '\nconst broken: string = 1;\n');
  run('prepack', 1);
  assert.deepEqual(inventory(target), original);
  assert(!fs.existsSync(transaction));
  console.log(
    'PASS clean/stale tarball equality, templates/licenses, modes/version, original ' +
      'restoration, valid/dangling relative symlinks, concurrent/repeated pack, ' +
      'changed-artifact refusal, compiler failure',
  );
} finally {
  fs.rmSync(scratch, { recursive: true, force: true });
}
