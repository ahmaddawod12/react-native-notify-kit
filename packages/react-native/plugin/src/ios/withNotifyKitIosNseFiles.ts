import * as fs from 'fs';
import * as path from 'path';

import type { NormalizedIosNotificationServiceExtensionOptions } from '../options';
import {
  DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  DEFAULT_NSE_MARKETING_VERSION,
  renderNotificationServiceSwift,
  renderNseEntitlementsPlist,
  renderNseInfoPlist,
} from '../shared/nse/initNseCore';
import type { ExpoConfigLike } from './withNotifyKitIosNseAppExtension';

type DangerousModConfig<TConfig extends ExpoConfigLike> = TConfig & {
  modRequest: {
    platformProjectRoot: string;
    [key: string]: unknown;
  };
};

type WithDangerousMod = <TConfig extends ExpoConfigLike>(
  config: TConfig,
  action: [
    'ios',
    (
      config: DangerousModConfig<TConfig>,
    ) => DangerousModConfig<TConfig> | Promise<DangerousModConfig<TConfig>>,
  ],
) => TConfig;

declare const require: {
  (id: string): unknown;
  resolve(id: string, options?: { paths?: string[] }): string;
};

declare const process: {
  cwd(): string;
};

const MARKETING_VERSION_MARKER = '__NOTIFYKIT_NSE_MARKETING_VERSION__';
const CURRENT_PROJECT_VERSION_MARKER = '__NOTIFYKIT_NSE_CURRENT_PROJECT_VERSION__';

interface NotifyKitNseVersionOptions {
  marketingVersion: string;
  currentProjectVersion: string;
}

export function withNotifyKitIosNseFiles<TConfig extends ExpoConfigLike>(
  config: TConfig,
  nseOptions: NormalizedIosNotificationServiceExtensionOptions,
): TConfig {
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

export function writeNotifyKitIosNseFiles(
  platformProjectRoot: string,
  targetName: string,
  versionOptions: NotifyKitNseVersionOptions = {
    marketingVersion: DEFAULT_NSE_MARKETING_VERSION,
    currentProjectVersion: DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  },
): void {
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

function resolveNseVersionOptions(config: ExpoConfigLike): NotifyKitNseVersionOptions {
  return {
    marketingVersion: optionalString(config.version) ?? DEFAULT_NSE_MARKETING_VERSION,
    currentProjectVersion:
      optionalString(config.ios?.buildNumber) ?? DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  };
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function isGeneratedNseInfoPlist(contents: string, targetName: string): boolean {
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

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function writeFileIfMissingOrIdentical(
  filePath: string,
  contents: string,
  canOverwrite?: (currentContents: string) => boolean,
): void {
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

function requireExpoConfigPlugins(): {
  withDangerousMod: WithDangerousMod;
} {
  try {
    return require('expo/config-plugins') as ReturnType<typeof requireExpoConfigPlugins>;
  } catch (error) {
    try {
      const expoConfigPluginsPath = require.resolve('expo/config-plugins', {
        paths: [process.cwd()],
      });

      return require(expoConfigPluginsPath) as ReturnType<typeof requireExpoConfigPlugins>;
    } catch {
      throw error;
    }
  }
}
