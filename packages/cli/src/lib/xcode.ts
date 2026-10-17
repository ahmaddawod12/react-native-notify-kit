/** Stable Cordova parser/writer; provenance and the narrow UUID patch live in vendor/xcode. */
export interface XcodeProject {
  parseSync(): void;
  parse(callback: (error: Error | null, hash?: unknown) => void): XcodeProject;
  writeSync(): string;
  generateUuid(): string;
  addTarget(
    name: string,
    type: string,
    subfolder: string,
  ): {
    uuid: string;
    pbxNativeTarget?: Record<string, unknown>;
  } | null;
  addBuildPhase(
    files: string[],
    buildPhaseType: string,
    comment: string,
    target?: string,
    optionOrFolderType?: string,
  ): void;
  addSourceFile(path: string, opts?: Record<string, unknown>, group?: string): void;
  addResourceFile(path: string, opts?: Record<string, unknown>, group?: string): void;
  addPbxGroup(
    files: string[],
    name: string,
    path: string,
  ): { uuid: string; pbxGroup: Record<string, unknown> };
  addTargetDependency(target: string, dependencyTargets: string[]): void;
  pbxNativeTargetSection(): Record<string, unknown>;
  pbxXCBuildConfigurationSection(): Record<string, unknown>;
  findPBXGroupKey(criteria: Record<string, string>): string | null;
}

// Keeping this relative path works in both src/lib and the compiled dist/lib.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const xcode: { project(path: string): XcodeProject } = require('../../vendor/xcode');

export default xcode;
