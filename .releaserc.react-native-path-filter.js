const path = require('path');

// Include sources used only while staging the npm package, as well as files
// already inside the published workspace.
const reactNativePaths = [
  'ios',
  'android',
  'packages/react-native',
  'packages/cli',
  'build_ios_core.sh',
  'scripts/prepack-cli.sh',
  'scripts/cli-artifacts.cjs',
];

Object.keys(require.cache)
  .filter(m => path.posix.normalize(m).endsWith('/node_modules/git-log-parser/src/index.js'))
  .forEach(moduleName => {
    const parse = require.cache[moduleName].exports.parse;
    require.cache[moduleName].exports.parse = (config, options) => {
      // set the `_` "raw value(s)" param of git-log-parser config to be an array
      // of raw values containing whatever is in there now, along with our filter paths
      // at the end so we only get git-log for those paths
      const raw = Array.isArray(config._) ? config._ : config._ ? [config._] : [];
      // Some historical paths no longer exist in a clean checkout. Without
      // this delimiter Git treats them as revisions and the parser drops commits.
      config._ = [...raw, ...(raw.includes('--') ? [] : ['--']), ...reactNativePaths];
      return parse(config, options);
    };
  });
