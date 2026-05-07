import * as fs from 'fs';
import * as path from 'path';

const BASIC_PODFILE = `platform :ios, '15.1'

target 'MyApp' do
  use_react_native!
end
`;

const enabledOptions = {
  enabled: true,
  targetName: 'NotifyKitNSE',
  bundleSuffix: '.NotifyKitNSE',
};

const repoRoot = path.resolve(__dirname, '../../../../..');
const expoSmokeRoot = path.join(repoRoot, 'apps/expo-smoke');
const expoSmokeIosRoot = path.join(expoSmokeRoot, 'ios');

function countOccurrences(content: string, needle: string): number {
  return content.split(needle).length - 1;
}

function getTopLevelTargetBlock(content: string, targetName: string): string {
  const escapedTargetName = targetName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = content.match(new RegExp(`^target '${escapedTargetName}' do\\n[\\s\\S]*?^end\\n?`, 'm'));
  return match?.[0] ?? '';
}

describe('NotifyKit Expo Podfile mod', () => {
  beforeEach(() => {
    jest.resetModules();
  });

  it('leaves config unchanged and does not register withPodfile when NSE is disabled', () => {
    const withPodfile = jest.fn();
    jest.doMock('expo/config-plugins', () => ({ withPodfile }), { virtual: true });

    const { withNotifyKitIosNsePodfile } = require('../ios/withNotifyKitIosNsePodfile');
    const config = {};

    expect(
      withNotifyKitIosNsePodfile(config, {
        ...enabledOptions,
        enabled: false,
      }),
    ).toBe(config);
    expect(withPodfile).not.toHaveBeenCalled();
  });

  it('patches modResults.contents when NSE is enabled', () => {
    const withPodfile = jest.fn((config, action) =>
      action({
        ...config,
        modRequest: {
          projectRoot: expoSmokeRoot,
          platformProjectRoot: expoSmokeIosRoot,
        },
        modResults: {
          contents: BASIC_PODFILE,
        },
      }),
    );
    jest.doMock('expo/config-plugins', () => ({ withPodfile }), { virtual: true });

    const { withNotifyKitIosNsePodfile } = require('../ios/withNotifyKitIosNsePodfile');
    const config = withNotifyKitIosNsePodfile({}, enabledOptions);

    expect(withPodfile).toHaveBeenCalledTimes(1);
    const contents = config.modResults.contents;
    const hostTargetBlock = getTopLevelTargetBlock(contents, 'MyApp');
    const nseTargetBlock = getTopLevelTargetBlock(contents, 'NotifyKitNSE');

    expect(contents).toMatch(/^target 'NotifyKitNSE' do/m);
    expect(contents).not.toMatch(/^  target 'NotifyKitNSE' do/m);
    expect(hostTargetBlock).not.toContain("target 'NotifyKitNSE' do");
    expect(nseTargetBlock).not.toContain('inherit! :search_paths');
    expect(nseTargetBlock).toContain(
      "pod 'RNNotifeeCore', :path => '../../../packages/react-native'",
    );
    expect(contents).toContain(
      'NotifyKitNSE: avoid an Xcode build cycle between the embedded app extension',
    );
  });

  it('keeps the Expo Podfile patch idempotent on repeated mod runs', () => {
    const withPodfile = jest.fn((config, action) => {
      const first = action({
        ...config,
        modRequest: {
          projectRoot: expoSmokeRoot,
          platformProjectRoot: expoSmokeIosRoot,
        },
        modResults: {
          contents: BASIC_PODFILE,
        },
      });

      return action({
        ...first,
        modRequest: {
          projectRoot: expoSmokeRoot,
          platformProjectRoot: expoSmokeIosRoot,
        },
        modResults: {
          contents: first.modResults.contents,
        },
      });
    });
    jest.doMock('expo/config-plugins', () => ({ withPodfile }), { virtual: true });

    const { withNotifyKitIosNsePodfile } = require('../ios/withNotifyKitIosNsePodfile');
    const config = withNotifyKitIosNsePodfile({}, enabledOptions);
    const contents = config.modResults.contents;
    const nseTargetBlock = getTopLevelTargetBlock(contents, 'NotifyKitNSE');

    expect(countOccurrences(contents, "target 'NotifyKitNSE' do")).toBe(1);
    expect(
      countOccurrences(
        contents,
        'NotifyKitNSE: avoid an Xcode build cycle between the embedded app extension',
      ),
    ).toBe(1);
    expect(nseTargetBlock).not.toContain('inherit! :search_paths');
  });

  it('passes the configured targetName to the Podfile patcher', () => {
    const withPodfile = jest.fn((config, action) =>
      action({
        ...config,
        modRequest: {
          projectRoot: expoSmokeRoot,
          platformProjectRoot: expoSmokeIosRoot,
        },
        modResults: {
          contents: BASIC_PODFILE,
        },
      }),
    );
    jest.doMock('expo/config-plugins', () => ({ withPodfile }), { virtual: true });

    const { withNotifyKitIosNsePodfile } = require('../ios/withNotifyKitIosNsePodfile');
    const config = withNotifyKitIosNsePodfile(
      {},
      {
        ...enabledOptions,
        targetName: 'CustomNotifyKitNSE',
      },
    );

    expect(config.modResults.contents).toContain("target 'CustomNotifyKitNSE' do");
    expect(config.modResults.contents).not.toContain("target 'NotifyKitNSE' do");
  });

  it('calculates packagePathFromIos from projectRoot and platformProjectRoot', () => {
    const { resolveNotifyKitPackagePathFromIos } = require('../ios/withNotifyKitIosNsePodfile');

    expect(resolveNotifyKitPackagePathFromIos(expoSmokeRoot, expoSmokeIosRoot)).toBe(
      '../../../packages/react-native',
    );
  });

  it('normalizes Windows backslashes in Podfile paths', () => {
    const { normalizePodfilePath } = require('../ios/withNotifyKitIosNsePodfile');

    expect(normalizePodfilePath('..\\..\\packages\\react-native')).toBe(
      '../../packages/react-native',
    );
  });

  it('does not import from the CLI implementation', () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, '../ios/withNotifyKitIosNsePodfile.ts'),
      'utf8',
    );

    expect(source).not.toMatch(/packages\/cli|\.\.\/\.\.\/\.\.\/cli|react-native-notify-kit\/cli/);
  });
});
