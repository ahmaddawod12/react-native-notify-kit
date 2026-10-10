import path from 'path';
import ts from 'typescript';

test('a public TypeScript consumer can use numeric, optional timeout metadata', () => {
  const repoRoot = path.resolve(__dirname, '../../..');
  const configPath = path.join(repoRoot, 'tsconfig.json');
  const config = ts.readConfigFile(configPath, ts.sys.readFile);
  expect(config.error).toBeUndefined();
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, repoRoot);
  expect(parsed.errors).toEqual([]);
  const options: ts.CompilerOptions = {
    ...parsed.options,
    noUnusedLocals: false,
    noUnusedParameters: false,
    paths: {
      ...parsed.options.paths,
      'react-native-notify-kit': [path.join(repoRoot, 'packages/react-native/src/index.ts')],
    },
  };
  const fixturePath = path.join(__dirname, 'fgTimeout.consumer.ts');
  const fixture = `
    import notifee, { Event, EventDetail, EventType } from 'react-native-notify-kit';
    type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends
      (<T>() => T extends B ? 1 : 2) ? true : false;
    type Assert<T extends true> = T;
    type StartId = Assert<Equal<EventDetail['startId'], number | undefined>>;
    type FgsType = Assert<Equal<EventDetail['fgsType'], number | undefined>>;
    const empty: EventDetail = {};
    const startOnly: EventDetail = { startId: 42 };
    const typeOnly: EventDetail = { fgsType: -1 };
    const event: Event = {
      type: EventType.FG_TIMEOUT,
      detail: { notification: { id: 'timeout' }, startId: 42, fgsType: -1 },
    };
    const legacy: Event = { type: EventType.PRESS, detail: { notification: { id: 'press' } } };
    // @ts-expect-error startId is numeric
    const stringStart: EventDetail = { startId: '42' };
    // @ts-expect-error fgsType is numeric
    const stringType: EventDetail = { fgsType: '-1' };
    // @ts-expect-error startId does not accept null
    const nullStart: EventDetail = { startId: null };
    // @ts-expect-error fgsType does not accept null
    const nullType: EventDetail = { fgsType: null };
    // @ts-expect-error fgsType does not accept boolean
    const booleanType: EventDetail = { fgsType: false };
    notifee.onForegroundEvent(({ type, detail }) => {
      if (type === EventType.FG_TIMEOUT) {
        const startId: number | undefined = detail.startId;
        const fgsType: number | undefined = detail.fgsType;
      }
    });
    notifee.onBackgroundEvent(async ({ type, detail }) => {
      if (type === EventType.FG_TIMEOUT) {
        const startId: number | undefined = detail.startId;
        const fgsType: number | undefined = detail.fgsType;
      }
    });
  `;
  const host = ts.createCompilerHost(options);
  const originalGetSourceFile = host.getSourceFile.bind(host);
  host.getSourceFile = (fileName, languageVersion, onError, shouldCreateNewSourceFile) =>
    fileName === fixturePath
      ? ts.createSourceFile(fileName, fixture, languageVersion, true)
      : originalGetSourceFile(fileName, languageVersion, onError, shouldCreateNewSourceFile);
  const program = ts.createProgram([fixturePath], options, host);
  const diagnostics = ts.getPreEmitDiagnostics(program);
  const formatted = ts.formatDiagnosticsWithColorAndContext(diagnostics, {
    getCanonicalFileName: fileName => fileName,
    getCurrentDirectory: () => repoRoot,
    getNewLine: () => '\n',
  });
  expect(formatted).toBe('');
});
