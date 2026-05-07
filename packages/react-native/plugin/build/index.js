'use strict';

const { createRunOncePlugin } = requireExpoConfigPlugins();
const { normalizeNotifyKitPluginOptions } = require('./options');
const {
  withNotifyKitIosNseAppExtension,
} = require('./ios/withNotifyKitIosNseAppExtension');
const pkg = require('../../package.json');

function withNotifyKit(config, props = {}) {
  const options = normalizeNotifyKitPluginOptions(props);

  return withNotifyKitIosNseAppExtension(
    config,
    options.ios.notificationServiceExtension,
  );
}

const plugin = createRunOncePlugin(withNotifyKit, pkg.name, pkg.version);

module.exports = plugin;
module.exports.default = plugin;
module.exports.withNotifyKit = withNotifyKit;
module.exports.normalizeNotifyKitPluginOptions = normalizeNotifyKitPluginOptions;
module.exports.withNotifyKitIosNseAppExtension = withNotifyKitIosNseAppExtension;

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
