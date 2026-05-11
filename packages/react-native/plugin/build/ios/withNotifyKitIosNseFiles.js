'use strict';

const fs = require('fs');
const path = require('path');

const {
  DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  DEFAULT_NSE_MARKETING_VERSION,
  renderNotificationServiceSwift,
  renderNseEntitlementsPlist,
  renderNseInfoPlist,
} = require('../shared/nse/initNseCore');

const MARKETING_VERSION_MARKER = '__NOTIFYKIT_NSE_MARKETING_VERSION__';
const CURRENT_PROJECT_VERSION_MARKER = '__NOTIFYKIT_NSE_CURRENT_PROJECT_VERSION__';

function withNotifyKitIosNseFiles(config, nseOptions) {
  if (!nseOptions.enabled) {
    return config;
  }

  const { withDangerousMod } = requireExpoConfigPlugins();

  return withDangerousMod(config, [
    'ios',
    modConfig => {
      writeNotifyKitIosNseFiles(
        modConfig.modRequest.platformProjectRoot,
        nseOptions.targetName,
        resolveNseVersionOptions(modConfig),
      );
      return modConfig;
    },
  ]);
}

function writeNotifyKitIosNseFiles(
  platformProjectRoot,
  targetName,
  versionOptions = {
    marketingVersion: DEFAULT_NSE_MARKETING_VERSION,
    currentProjectVersion: DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  },
) {
  const targetDir = path.join(platformProjectRoot, targetName);
  fs.mkdirSync(targetDir, { recursive: true });

  writeFileIfMissingOrIdentical(
    path.join(targetDir, 'NotificationService.swift'),
    renderNotificationServiceSwift(),
  );
  writeFileIfMissingOrIdentical(
    path.join(targetDir, 'Info.plist'),
    renderNseInfoPlist({ targetName, ...versionOptions }),
    contents => isGeneratedNseInfoPlist(contents, targetName),
  );
  writeFileIfMissingOrIdentical(
    path.join(targetDir, `${targetName}.entitlements`),
    renderNseEntitlementsPlist(),
  );
}

function resolveNseVersionOptions(config) {
  return {
    marketingVersion: optionalString(config.version) ?? DEFAULT_NSE_MARKETING_VERSION,
    currentProjectVersion:
      optionalString(config.ios?.buildNumber) ?? DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  };
}

function optionalString(value) {
  return typeof value === 'string' ? value : undefined;
}

function isGeneratedNseInfoPlist(contents, targetName) {
  const templateWithMarkers = renderNseInfoPlist({
    targetName,
    marketingVersion: MARKETING_VERSION_MARKER,
    currentProjectVersion: CURRENT_PROJECT_VERSION_MARKER,
  });
  const escapedTemplate = escapeRegExp(templateWithMarkers)
    .replace(MARKETING_VERSION_MARKER, '[^<]*')
    .replace(CURRENT_PROJECT_VERSION_MARKER, '[^<]*');

  return new RegExp(`^${escapedTemplate}$`).test(contents);
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function writeFileIfMissingOrIdentical(filePath, contents, canOverwrite) {
  if (fs.existsSync(filePath)) {
    const currentContents = fs.readFileSync(filePath, 'utf8');
    if (currentContents !== contents) {
      if (canOverwrite?.(currentContents)) {
        fs.writeFileSync(filePath, contents, 'utf8');
        return;
      }

      throw new Error(
        `[react-native-notify-kit] Refusing to overwrite existing ${filePath}. ` +
          'Delete it or make it match the generated NotifyKit NSE template.',
      );
    }
    return;
  }

  fs.writeFileSync(filePath, contents, 'utf8');
}

function requireExpoConfigPlugins() {
  try {
    return require('expo/config-plugins');
  } catch (error) {
    try {
      return require(require.resolve('expo/config-plugins', { paths: [process.cwd()] }));
    } catch {
      throw error;
    }
  }
}

module.exports = {
  withNotifyKitIosNseFiles,
  writeNotifyKitIosNseFiles,
};
