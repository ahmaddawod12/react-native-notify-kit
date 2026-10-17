import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import xcode from '../lib/xcode';
import { detectIosProject } from '../lib/detectProject';
import { patchXcodeProjectForNotifyKitNse } from '../lib/patchXcodeProject';

const fixture = path.join(__dirname, 'fixtures/sample-rn-app/ios/MyApp.xcodeproj/project.pbxproj');

describe('stable Xcode compatibility layer', () => {
  let directory: string;
  let filename: string;
  beforeEach(() => {
    directory = fs.mkdtempSync(path.join(os.tmpdir(), 'notify-kit-xcode-compat-'));
    fs.mkdirSync(path.join(directory, 'Acme Project.xcodeproj'));
    filename = path.join(directory, 'Acme Project.xcodeproj/project.pbxproj');
    fs.copyFileSync(fixture, filename);
  });
  afterEach(() => fs.rmSync(directory, { recursive: true, force: true }));

  it('keeps the worker parser equivalent to synchronous parsing', async () => {
    const synchronous = xcode.project(filename);
    synchronous.parseSync();
    const worker = xcode.project(filename);
    await new Promise<void>((resolve, reject) =>
      worker.parse(error => (error ? reject(error) : resolve())),
    );
    expect((worker as any).hash).toEqual((synchronous as any).hash);
    expect(worker.writeSync()).toBe(synchronous.writeSync());
  });

  it('preserves existing project objects, comments and unrelated settings with multiple targets', () => {
    const project = xcode.project(filename);
    project.parseSync();
    project.addTarget('Auxiliary-App', 'application', 'Auxiliary-App');
    project.addTarget('ExistingNSE', 'app_extension', 'ExistingNSE');
    const objects = (project as any).hash.project.objects;
    const mainEntry = Object.entries(objects.PBXNativeTarget).find(
      ([, value]) => typeof value === 'object' && (value as any).name === 'NotifeeExample',
    );
    expect(mainEntry).toBeDefined();
    const [hostUuid, host] = mainEntry as [string, any];
    host.name = '"Acme App (Beta)"';
    const before = JSON.parse(JSON.stringify(objects));
    const result = patchXcodeProjectForNotifyKitNse(project, {
      targetName: 'Notify-Kit.NSE',
      bundleIdentifier: 'com.acme.notify.nse',
      parentTargetName: 'Acme App (Beta)',
    });
    expect(result.hostTargetUuid).toBe(hostUuid);

    for (const [section, entries] of Object.entries(before)) {
      for (const [uuid, oldValue] of Object.entries(entries as Record<string, any>)) {
        const current = JSON.parse(JSON.stringify(objects[section][uuid]));
        if (typeof oldValue === 'object') {
          if (section === 'PBXNativeTarget' && uuid === hostUuid) {
            delete oldValue.buildPhases;
            delete oldValue.dependencies;
            delete current.buildPhases;
            delete current.dependencies;
          } else if (section === 'PBXProject') {
            delete oldValue.targets;
            delete current.targets;
          } else if (section === 'PBXGroup') {
            // Only the products group acquires the new .appex reference.
            expect(current.children.slice(0, oldValue.children.length)).toEqual(oldValue.children);
            delete oldValue.children;
            delete current.children;
          } else if (
            section === 'PBXShellScriptBuildPhase' &&
            oldValue.name === '"[CP-User] [RNFB] Core Configuration"'
          ) {
            delete oldValue.inputPaths;
            delete current.inputPaths;
          } else if (section === 'PBXCopyFilesBuildPhase') {
            // Stable upstream adds the new product to the existing copy phase.
            expect(current.files.slice(0, oldValue.files.length)).toEqual(oldValue.files);
            delete oldValue.files;
            delete current.files;
          }
        }
        expect(current).toEqual(oldValue);
      }
    }

    fs.writeFileSync(filename, project.writeSync());
    const parsed = xcode.project(filename);
    parsed.parseSync();
    expect(detectIosProject(directory).parentTargetName).toBe('Acme App (Beta)');
    const serialized = parsed.writeSync();
    const fileRefs = (parsed as any).hash.project.objects.PBXFileReference;
    const newSource = Object.values(fileRefs).find(
      (value: any) => value?.path === '"Notify-Kit.NSE/NotificationService.swift"',
    ) as any;
    expect(newSource).toBeDefined();
    expect(newSource).not.toHaveProperty('explicitFileType');
    expect(
      patchXcodeProjectForNotifyKitNse(parsed, {
        targetName: 'Notify-Kit.NSE',
        bundleIdentifier: 'com.acme.notify.nse',
      }),
    ).toEqual({ didChange: false, warnings: [] });
    expect(parsed.writeSync()).toBe(serialized);
    const targetNames = Object.values(parsed.pbxNativeTargetSection())
      .filter(value => typeof value === 'object')
      .map(value => (value as any).name);
    expect(targetNames).toEqual([
      '"Acme App (Beta)"',
      '"Auxiliary-App"',
      '"ExistingNSE"',
      '"Notify-Kit.NSE"',
    ]);
  });

  it('reports malformed projects without changing their bytes', () => {
    fs.writeFileSync(filename, '// !$*UTF8*$!\n{ objects = { malformed');
    const original = fs.readFileSync(filename);
    expect(() => detectIosProject(directory)).toThrow();
    expect(fs.readFileSync(filename)).toEqual(original);
  });

  it('produces unique uppercase 24-character Xcode IDs without the uuid package', () => {
    const project = xcode.project(filename);
    project.parseSync();
    const values = Array.from({ length: 100 }, () => project.generateUuid());
    expect(values.every(value => /^[A-F0-9]{24}$/.test(value))).toBe(true);
    expect(new Set(values).size).toBe(values.length);
    const manifest = JSON.parse(
      fs.readFileSync(path.join(__dirname, '../../package.json'), 'utf8'),
    );
    expect(manifest.dependencies).not.toHaveProperty('xcode');
    expect(manifest.dependencies).not.toHaveProperty('uuid');
    const upstream = fs.readFileSync(
      path.join(__dirname, '../../vendor/xcode/lib/pbxProject.js'),
      'utf8',
    );
    expect(upstream).toContain('crypto.randomUUID()');
    expect(upstream).not.toContain("require('uuid')");
  });
});
