const { IOSConfig, withDangerousMod } = require('@expo/config-plugins');
const fs = require('node:fs');

const FIREBASE_IMPORT = 'import FirebaseCore';
const FIREBASE_CONFIGURE = 'FirebaseApp.configure()';

const addFirebaseImport = contents => {
  if (contents.includes(FIREBASE_IMPORT)) {
    return contents;
  }

  const lines = contents.split('\n');
  const expoImportIndex = lines.findIndex(line => {
    const trimmed = line.trim();
    return trimmed === 'import Expo' || trimmed === 'internal import Expo';
  });

  if (expoImportIndex >= 0) {
    lines.splice(expoImportIndex + 1, 0, FIREBASE_IMPORT);
    return lines.join('\n');
  }

  const firstImportIndex = lines.findIndex(line => /^(?:\w+\s+)?import\s+/.test(line.trim()));

  if (firstImportIndex >= 0) {
    lines.splice(firstImportIndex + 1, 0, FIREBASE_IMPORT);
    return lines.join('\n');
  }

  return `${FIREBASE_IMPORT}\n${contents}`;
};

const addFirebaseConfigure = contents => {
  if (contents.includes(FIREBASE_CONFIGURE)) {
    return contents;
  }

  const methodIndex = contents.indexOf('didFinishLaunchingWithOptions');

  if (methodIndex < 0) {
    throw new Error(
      '[expo-smoke] Unable to find didFinishLaunchingWithOptions in AppDelegate.swift for Firebase setup.',
    );
  }

  const openingBraceIndex = contents.indexOf('{', methodIndex);

  if (openingBraceIndex < 0) {
    throw new Error(
      '[expo-smoke] Unable to find didFinishLaunchingWithOptions body in AppDelegate.swift for Firebase setup.',
    );
  }

  const insertionIndex = contents.indexOf('\n', openingBraceIndex);

  if (insertionIndex < 0) {
    throw new Error('[expo-smoke] Unable to insert FirebaseApp.configure() in AppDelegate.swift.');
  }

  const remainingContents = contents.slice(insertionIndex + 1);
  const indentation = remainingContents.match(/^(\s*)/)?.[1] ?? '  ';

  return (
    contents.slice(0, insertionIndex + 1) +
    `${indentation}${FIREBASE_CONFIGURE}\n` +
    remainingContents
  );
};

const patchSwiftAppDelegate = contents => addFirebaseConfigure(addFirebaseImport(contents));

module.exports = config =>
  withDangerousMod(config, [
    'ios',
    async configWithMods => {
      const appDelegate = IOSConfig.Paths.getAppDelegate(configWithMods.modRequest.projectRoot);

      if (appDelegate.language !== 'swift') {
        throw new Error('[expo-smoke] Firebase AppDelegate plugin expected a Swift AppDelegate.');
      }

      const contents = fs.readFileSync(appDelegate.path, 'utf8');
      const updatedContents = patchSwiftAppDelegate(contents);

      if (updatedContents !== contents) {
        fs.writeFileSync(appDelegate.path, updatedContents);
      }

      return configWithMods;
    },
  ]);
