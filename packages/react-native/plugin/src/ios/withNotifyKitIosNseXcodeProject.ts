import type { NormalizedIosNotificationServiceExtensionOptions } from '../options';
import {
  DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  DEFAULT_NSE_MARKETING_VERSION,
} from '../shared/nse/initNseCore';
import {
  patchXcodeProjectForNotifyKitNse,
  type XcodeProject,
} from '../shared/nse/patchXcodeProject';
import {
  resolveNotifyKitIosNseBundleIdentifier,
  type ExpoConfigLike,
} from './withNotifyKitIosNseAppExtension';

type XcodeProjectModConfig<TConfig extends ExpoConfigLike> = TConfig & {
  modRequest: {
    projectName?: string;
    [key: string]: unknown;
  };
  modResults: XcodeProject;
};

type WithXcodeProject = <TConfig extends ExpoConfigLike>(
  config: TConfig,
  action: (
    config: XcodeProjectModConfig<TConfig>,
  ) => XcodeProjectModConfig<TConfig> | Promise<XcodeProjectModConfig<TConfig>>,
) => TConfig;

declare const require: {
  (id: string): unknown;
  resolve(id: string, options?: { paths?: string[] }): string;
};

declare const process: {
  cwd(): string;
};

interface NotifyKitNseVersionOptions {
  marketingVersion: string;
  currentProjectVersion: string;
}

export function withNotifyKitIosNseXcodeProject<TConfig extends ExpoConfigLike>(
  config: TConfig,
  nseOptions: NormalizedIosNotificationServiceExtensionOptions,
): TConfig {
  if (!nseOptions.enabled) {
    return config;
  }

  const { withXcodeProject } = requireExpoConfigPlugins();
  const bundleIdentifier = resolveNotifyKitIosNseBundleIdentifier(config, nseOptions);

  return withXcodeProject(config, modConfig => {
    const result = patchXcodeProjectForNotifyKitNse(modConfig.modResults, {
      targetName: nseOptions.targetName,
      bundleIdentifier,
      parentTargetName: modConfig.modRequest.projectName,
      ...resolveNseVersionOptions(modConfig),
    });

    if (result.didChange && !result.hostTargetUuid) {
      throw new Error(
        '[react-native-notify-kit] Failed to link NotifyKit NSE target to the host app target.',
      );
    }

    return modConfig;
  });
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

function requireExpoConfigPlugins(): {
  withXcodeProject: WithXcodeProject;
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
