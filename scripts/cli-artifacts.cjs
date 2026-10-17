#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const repoRoot = path.resolve(__dirname, '..');
const cliDir = path.join(repoRoot, 'packages/cli');
const target = path.join(repoRoot, 'packages/react-native/cli');
// Outside the public package: neither backups nor transaction data can be packed.
const transaction = path.join(repoRoot, 'packages/.cli-pack-transaction');
const stateFile = path.join(transaction, 'state.json');
const backup = path.join(transaction, 'original');
const generated = path.join(transaction, 'generated');

function entryExists(filename) {
  try {
    fs.lstatSync(filename);
    return true;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

function inventory(directory) {
  const entries = {};
  function visit(current, relative) {
    for (const name of fs.readdirSync(current).sort()) {
      const filename = path.join(current, name);
      const key = relative ? `${relative}/${name}` : name;
      const stat = fs.lstatSync(filename);
      if (stat.isDirectory()) {
        entries[key] = { type: 'directory', mode: stat.mode & 0o777 };
        visit(filename, key);
      } else if (stat.isFile()) {
        entries[key] = {
          type: 'file',
          mode: stat.mode & 0o777,
          sha256: crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex'),
        };
      } else {
        throw new Error(`Unexpected non-regular generated artifact: ${filename}`);
      }
    }
  }
  visit(directory, '');
  return entries;
}

function build() {
  const buildDir = path.join(transaction, 'build');
  const tsc = require.resolve('typescript/bin/tsc', { paths: [cliDir] });
  // Ignored dist is never read; all generated output is transaction-owned.
  execFileSync(
    process.execPath,
    [
      tsc,
      '--project',
      path.join(cliDir, 'tsconfig.json'),
      '--outDir',
      buildDir,
      '--sourceMap',
      'false',
      '--declaration',
      'false',
    ],
    {
      cwd: cliDir,
      stdio: 'inherit',
    },
  );
  fs.mkdirSync(generated);
  fs.cpSync(buildDir, path.join(generated, 'dist'), { recursive: true });
  fs.cpSync(path.join(cliDir, 'src/templates'), path.join(generated, 'dist/templates'), {
    recursive: true,
  });
  fs.cpSync(path.join(cliDir, 'vendor'), path.join(generated, 'vendor'), { recursive: true });
  fs.mkdirSync(path.join(generated, 'bin'));
  fs.writeFileSync(
    path.join(generated, 'bin/react-native-notify-kit'),
    "#!/usr/bin/env node\nrequire('../dist/cli.js');\n",
    { mode: 0o755 },
  );
  const { version } = JSON.parse(
    fs.readFileSync(path.join(repoRoot, 'packages/react-native/package.json'), 'utf8'),
  );
  fs.writeFileSync(path.join(generated, 'package.json'), JSON.stringify({ version }) + '\n');
  for (const required of [
    'dist/cli.js',
    'dist/templates/NotificationService.swift.tmpl',
    'dist/templates/Info.plist.tmpl',
    'dist/templates/NotifyKitNSE.entitlements.tmpl',
    'vendor/xcode/LICENSE',
    'vendor/xcode/NOTICE',
    'vendor/xcode/lib/parseJob.js',
    'vendor/xcode/lib/parser/pbxproj.js',
  ]) {
    if (!fs.statSync(path.join(generated, required)).isFile()) {
      throw new Error(`Missing CLI artifact: ${required}`);
    }
  }
  // Git's executable bit and the artifact contract determine permissions,
  // independently of a maintainer's umask.
  function normalizeModes(directory) {
    fs.chmodSync(directory, 0o755);
    for (const entry of fs.readdirSync(directory)) {
      const filename = path.join(directory, entry);
      if (fs.lstatSync(filename).isDirectory()) normalizeModes(filename);
      else
        fs.chmodSync(
          filename,
          filename === path.join(generated, 'bin/react-native-notify-kit') ? 0o755 : 0o644,
        );
    }
  }
  normalizeModes(generated);
}

function prepack() {
  try {
    fs.mkdirSync(transaction);
  } catch (error) {
    if (error.code === 'EEXIST') {
      throw new Error(
        `CLI pack transaction already exists at ${transaction}. ` +
          'Finish the previous pack with the postpack command; existing files were preserved.',
      );
    }
    throw error;
  }
  let originalMoved = false;
  let generatedMoved = false;
  try {
    build();
    // Preserve directory entries, including dangling symlinks. Following the
    // link would mistake a renamed relative link for a missing original.
    const state = { version: 1, hadOriginal: entryExists(target), files: inventory(generated) };
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2) + '\n', { flag: 'wx' });
    if (state.hadOriginal) {
      fs.renameSync(target, backup);
      originalMoved = true;
    }
    fs.renameSync(generated, target);
    generatedMoved = true;
    console.log('[prepack-cli] Fresh CLI built; existing cli path preserved until postpack.');
  } catch (error) {
    // Only remove directories created by this invocation. Never discard the original.
    if (generatedMoved) fs.renameSync(target, generated);
    if (originalMoved) fs.renameSync(backup, target);
    fs.rmSync(transaction, { recursive: true });
    throw error;
  }
}

function postpack() {
  if (!entryExists(transaction)) return;
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  if (state.version !== 1 || typeof state.hadOriginal !== 'boolean' || !state.files) {
    throw new Error('Invalid CLI pack state; refusing to remove files.');
  }
  // Keep files added/modified after prepack, including interrupted packing.
  if (JSON.stringify(inventory(target)) !== JSON.stringify(state.files)) {
    throw new Error(
      'CLI artifacts changed after prepack; refusing cleanup. ' +
        `Original files remain at ${backup}.`,
    );
  }
  if (state.hadOriginal && !entryExists(backup)) {
    throw new Error('Original CLI backup is missing; refusing cleanup.');
  }
  fs.renameSync(target, generated);
  if (state.hadOriginal) fs.renameSync(backup, target);
  fs.rmSync(transaction, { recursive: true });
  console.log('[postpack-cli] Generated CLI removed; original cli path restored.');
}

if (require.main === module) {
  try {
    if (process.argv[2] === 'prepack') prepack();
    else if (process.argv[2] === 'postpack') postpack();
    else throw new Error('Usage: cli-artifacts.cjs prepack|postpack');
  } catch (error) {
    console.error(`[cli-artifacts] ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = { inventory };
