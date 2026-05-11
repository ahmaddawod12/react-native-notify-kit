import * as crypto from 'crypto';

import {
  DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  DEFAULT_NSE_MARKETING_VERSION,
} from './initNseCore';

export interface XcodeProject {
  addTarget(
    name: string,
    type: string,
    subfolder: string,
  ): { uuid: string; pbxNativeTarget?: Record<string, unknown> } | null;
  addBuildPhase(
    files: string[],
    buildPhaseType: string,
    comment: string,
    target?: string,
    optionOrFolderType?: string,
  ): void;
  addPbxGroup(
    files: string[],
    name: string,
    path: string,
  ): { uuid: string; pbxGroup?: Record<string, unknown> };
  addSourceFile(path: string, opts?: Record<string, unknown>, group?: string): void;
  pbxNativeTargetSection(): Record<string, unknown>;
  pbxXCBuildConfigurationSection(): Record<string, unknown>;
}

export interface NotifyKitNseXcodePatchOptions {
  targetName: string;
  bundleIdentifier: string;
  parentTargetName?: string;
  deploymentTarget?: string;
  marketingVersion?: string;
  currentProjectVersion?: string;
}

export interface NotifyKitNseXcodePatchResult {
  didChange: boolean;
  targetUuid?: string;
  productUuid?: string;
  hostTargetUuid?: string;
  warnings: string[];
}

export function patchXcodeProjectForNotifyKitNse(
  proj: XcodeProject,
  options: NotifyKitNseXcodePatchOptions,
): NotifyKitNseXcodePatchResult {
  const {
    targetName,
    bundleIdentifier,
    parentTargetName,
    deploymentTarget = '15.1',
    marketingVersion = DEFAULT_NSE_MARKETING_VERSION,
    currentProjectVersion = DEFAULT_NSE_CURRENT_PROJECT_VERSION,
  } = options;
  const warnings: string[] = [];
  const existingTargetUuid = findTargetByName(proj, targetName);

  if (existingTargetUuid) {
    const didChange = setBuildSettings(
      proj,
      existingTargetUuid,
      targetName,
      bundleIdentifier,
      deploymentTarget,
      marketingVersion,
      currentProjectVersion,
    );

    if (!didChange) {
      return { didChange: false, warnings };
    }

    return {
      didChange: true,
      targetUuid: existingTargetUuid,
      hostTargetUuid: findHostTarget(proj, parentTargetName) ?? undefined,
      warnings,
    };
  }

  const target = proj.addTarget(targetName, 'app_extension', targetName);

  if (!target || !target.uuid) {
    throw new Error(`xcode library failed to create target '${targetName}'`);
  }

  const targetUuid = target.uuid;
  const productUuid = getTargetProductUuid(target);

  fixProductFileReference(proj, targetName);

  proj.addBuildPhase([], 'PBXSourcesBuildPhase', 'Sources', targetUuid);
  proj.addBuildPhase([], 'PBXFrameworksBuildPhase', 'Frameworks', targetUuid);
  proj.addBuildPhase([], 'PBXResourcesBuildPhase', 'Resources', targetUuid);

  const group = proj.addPbxGroup([], targetName, targetName);
  proj.addSourceFile(
    `${targetName}/NotificationService.swift`,
    {
      target: targetUuid,
    },
    group.uuid,
  );

  const hostUuid = findHostTarget(proj, parentTargetName);
  if (hostUuid) {
    addTargetDependencyManual(proj, hostUuid, targetUuid, targetName);
    stripRnfbInfoPlistInputPath(proj, hostUuid);
  }

  setBuildSettings(
    proj,
    targetUuid,
    targetName,
    bundleIdentifier,
    deploymentTarget,
    marketingVersion,
    currentProjectVersion,
  );

  return {
    didChange: true,
    targetUuid,
    productUuid,
    hostTargetUuid: hostUuid ?? undefined,
    warnings,
  };
}

function findHostTarget(proj: XcodeProject, parentTargetName?: string): string | null {
  if (parentTargetName) {
    return findTargetByName(proj, parentTargetName);
  }

  const targets = proj.pbxNativeTargetSection();
  for (const [key, value] of Object.entries(targets)) {
    if (typeof value !== 'object' || key.endsWith('_comment')) continue;
    const target = value as Record<string, unknown>;
    const productType = String(target.productType ?? '');
    if (productType.includes('application')) {
      return key;
    }
  }
  return null;
}

function findTargetByName(proj: XcodeProject, name: string): string | null {
  const targets = proj.pbxNativeTargetSection();
  for (const [key, value] of Object.entries(targets)) {
    if (typeof value !== 'object') continue;
    const target = value as Record<string, unknown>;
    const targetName = String(target.name ?? '').replace(/"/g, '');
    if (targetName === name) {
      return key;
    }
  }
  return null;
}

function getTargetProductUuid(target: {
  uuid: string;
  pbxNativeTarget?: Record<string, unknown>;
}): string | undefined {
  const productReference = target.pbxNativeTarget?.productReference;
  return typeof productReference === 'string' ? productReference : undefined;
}

function setBuildSettings(
  proj: XcodeProject,
  targetUuid: string,
  targetName: string,
  bundleId: string,
  deploymentTarget: string,
  marketingVersion: string,
  currentProjectVersion: string,
): boolean {
  let didChange = false;

  for (const settings of getTargetBuildSettings(proj, targetUuid, targetName)) {
    didChange =
      setBuildSetting(settings, 'INFOPLIST_FILE', `"${targetName}/Info.plist"`) || didChange;
    didChange =
      setBuildSetting(settings, 'PRODUCT_BUNDLE_IDENTIFIER', `"${bundleId}"`) || didChange;
    didChange = setBuildSetting(settings, 'TARGETED_DEVICE_FAMILY', `"1,2"`) || didChange;
    didChange =
      setBuildSetting(settings, 'IPHONEOS_DEPLOYMENT_TARGET', deploymentTarget) || didChange;
    didChange = setBuildSetting(settings, 'MARKETING_VERSION', marketingVersion) || didChange;
    didChange =
      setBuildSetting(settings, 'CURRENT_PROJECT_VERSION', currentProjectVersion) || didChange;
    didChange = setBuildSetting(settings, 'SWIFT_VERSION', '5.0') || didChange;
    didChange =
      setBuildSetting(
        settings,
        'CODE_SIGN_ENTITLEMENTS',
        `"${targetName}/${targetName}.entitlements"`,
      ) || didChange;
    didChange = setBuildSetting(settings, 'GENERATE_INFOPLIST_FILE', 'NO') || didChange;
  }

  return didChange;
}

function getTargetBuildSettings(
  proj: XcodeProject,
  targetUuid: string,
  targetName: string,
): Array<Record<string, string>> {
  const target = proj.pbxNativeTargetSection()[targetUuid] as Record<string, unknown> | undefined;
  const buildConfigurationListUuid = target?.buildConfigurationList;
  const configs = proj.pbxXCBuildConfigurationSection();
  const settingsByConfigurationList = getBuildSettingsFromConfigurationList(
    proj,
    typeof buildConfigurationListUuid === 'string' ? buildConfigurationListUuid : undefined,
    configs,
  );

  if (settingsByConfigurationList.length > 0) {
    return settingsByConfigurationList;
  }

  return Object.entries(configs)
    .filter(([, value]) => typeof value === 'object' && value !== null)
    .map(([, value]) => value as Record<string, unknown>)
    .filter(config => {
      const settings = config.buildSettings as Record<string, string> | undefined;
      return settings?.PRODUCT_NAME === `"${targetName}"`;
    })
    .map(config => config.buildSettings as Record<string, string>);
}

function getBuildSettingsFromConfigurationList(
  proj: XcodeProject,
  buildConfigurationListUuid: string | undefined,
  configs: Record<string, unknown>,
): Array<Record<string, string>> {
  if (!buildConfigurationListUuid) {
    return [];
  }

  const configurationLists = (proj as any).hash.project.objects.XCConfigurationList as
    | Record<string, unknown>
    | undefined;
  const configurationList = configurationLists?.[buildConfigurationListUuid] as
    | Record<string, unknown>
    | undefined;
  const buildConfigurations = Array.isArray(configurationList?.buildConfigurations)
    ? configurationList.buildConfigurations
    : [];

  return buildConfigurations
    .map(ref => (ref as Record<string, unknown>)?.value)
    .filter((uuid): uuid is string => typeof uuid === 'string')
    .map(uuid => configs[uuid])
    .filter(
      (config): config is Record<string, unknown> => typeof config === 'object' && config !== null,
    )
    .map(config => {
      if (typeof config.buildSettings !== 'object' || config.buildSettings === null) {
        config.buildSettings = {};
      }
      return config.buildSettings as Record<string, string>;
    });
}

function setBuildSetting(settings: Record<string, string>, key: string, value: string): boolean {
  if (settings[key] === value) {
    return false;
  }

  settings[key] = value;
  return true;
}

function fixProductFileReference(proj: XcodeProject, targetName: string): void {
  const fileRefs = (proj as any).hash.project.objects.PBXFileReference;
  if (!fileRefs) return;
  for (const [, value] of Object.entries(fileRefs)) {
    if (typeof value !== 'object') continue;
    const ref = value as Record<string, unknown>;
    if (String(ref.name ?? '').replace(/"/g, '') === `${targetName}.appex`) {
      delete ref.fileEncoding;
      delete ref.lastKnownFileType;
    }
  }
}

function genUuid(): string {
  return crypto.randomBytes(12).toString('hex').toUpperCase().slice(0, 24);
}

function addTargetDependencyManual(
  proj: XcodeProject,
  hostUuid: string,
  extensionUuid: string,
  extensionName: string,
): void {
  const proxyUuid = genUuid();
  const depUuid = genUuid();

  const objects = (proj as any).hash.project.objects;

  const rootObject = (proj as any).hash.project.rootObject as string;

  if (!objects.PBXContainerItemProxy) objects.PBXContainerItemProxy = {};
  objects.PBXContainerItemProxy[proxyUuid] = {
    isa: 'PBXContainerItemProxy',
    containerPortal: rootObject,
    proxyType: 1,
    remoteGlobalIDString: extensionUuid,
    remoteInfo: `"${extensionName}"`,
  };
  objects.PBXContainerItemProxy[`${proxyUuid}_comment`] = 'PBXContainerItemProxy';

  if (!objects.PBXTargetDependency) objects.PBXTargetDependency = {};
  objects.PBXTargetDependency[depUuid] = {
    isa: 'PBXTargetDependency',
    target: extensionUuid,
    targetProxy: proxyUuid,
  };
  objects.PBXTargetDependency[`${depUuid}_comment`] = 'PBXTargetDependency';

  const hostTarget = objects.PBXNativeTarget[hostUuid];
  if (hostTarget && Array.isArray(hostTarget.dependencies)) {
    hostTarget.dependencies.push({ value: depUuid, comment: 'PBXTargetDependency' });
  }
}

function stripRnfbInfoPlistInputPath(proj: XcodeProject, hostUuid: string): void {
  const objects = (proj as any).hash.project.objects;
  const hostTarget = objects.PBXNativeTarget?.[hostUuid];
  const shellPhases = objects.PBXShellScriptBuildPhase;

  if (!hostTarget || !Array.isArray(hostTarget.buildPhases) || !shellPhases) {
    return;
  }

  for (const phaseRef of hostTarget.buildPhases) {
    const phaseUuid = phaseRef?.value;
    if (!phaseUuid) continue;

    const phase = shellPhases[phaseUuid] as Record<string, unknown> | undefined;
    if (!phase) continue;

    const phaseName = String(phase.name ?? '').replace(/"/g, '');
    if (phaseName !== '[CP-User] [RNFB] Core Configuration') {
      continue;
    }

    const inputPaths = Array.isArray(phase.inputPaths) ? phase.inputPaths : [];
    const filteredInputPaths = inputPaths.filter(
      entry => String(entry) !== '"$(BUILT_PRODUCTS_DIR)/$(INFOPLIST_PATH)"',
    );

    if (filteredInputPaths.length > 0) {
      phase.inputPaths = filteredInputPaths;
    } else {
      delete phase.inputPaths;
    }
  }
}
