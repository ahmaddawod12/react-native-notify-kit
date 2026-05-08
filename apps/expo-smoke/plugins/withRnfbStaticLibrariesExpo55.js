const { withPodfile } = require('expo/config-plugins');

const MARKER = '# NotifyKit Expo smoke: force RNFirebase pods to static libraries';
const EXISTING_PRE_INSTALL_ERROR =
  '[react-native-notify-kit] apps/expo-smoke cannot safely patch an existing Podfile pre_install block for RNFirebase static libraries.';

const RNFB_STATIC_LIBRARY_PRE_INSTALL = `${MARKER}
pre_install do |installer|
  installer.pod_targets.each do |pod|
    next unless ['RNFBApp', 'RNFBMessaging'].include?(pod.name)

    def pod.build_type
      Pod::BuildType.static_library
    end
  end
end`;

const hasPreInstallBlock = contents => /^\s*pre_install\s+do\b/m.test(contents);

const insertBeforeFirstTarget = (contents, insertion) => {
  const targetMatch = contents.match(/^target\s+['"][^'"]+['"]\s+do\b/m);

  if (!targetMatch || targetMatch.index == null) {
    return `${contents.trimEnd()}\n\n${insertion}\n`;
  }

  return `${contents.slice(0, targetMatch.index)}${insertion}\n\n${contents.slice(targetMatch.index)}`;
};

const patchPodfileForRnfbStaticLibraries = contents => {
  if (contents.includes(MARKER)) {
    return contents;
  }

  if (hasPreInstallBlock(contents)) {
    throw new Error(EXISTING_PRE_INSTALL_ERROR);
  }

  return insertBeforeFirstTarget(contents, RNFB_STATIC_LIBRARY_PRE_INSTALL);
};

module.exports = config =>
  withPodfile(config, configWithMods => {
    configWithMods.modResults.contents = patchPodfileForRnfbStaticLibraries(
      configWithMods.modResults.contents,
    );

    return configWithMods;
  });
