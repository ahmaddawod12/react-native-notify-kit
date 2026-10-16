import { Image, Platform } from 'react-native';
import NotifeeApiModule from 'react-native-notify-kit/src/NotifeeApiModule';
import { Notification } from 'react-native-notify-kit/src/types/Notification';
import { TriggerType } from 'react-native-notify-kit/src/types/Trigger';
import validateAndroidNotification from 'react-native-notify-kit/src/validators/validateAndroidNotification';
import { validateAndroidBigPictureStyle } from 'react-native-notify-kit/src/validators/validateAndroidStyle';
import validateIOSAttachment from 'react-native-notify-kit/src/validators/validateIOSAttachment';
import { AndroidStyle } from 'react-native-notify-kit/src/types/NotificationAndroid';
import { setPlatform } from '../testSetup';

jest.mock('react-native-notify-kit/src/NotifeeNativeModule');

const { mockNotifeeNativeModule } = jest.requireMock<
  typeof import('../../src/__mocks__/NotifeeNativeModule')
>('react-native-notify-kit/src/NotifeeNativeModule');

const resolveAssetSource = jest.requireActual<{ default: typeof Image.resolveAssetSource }>(
  'react-native/Libraries/Image/resolveAssetSource',
).default;

// Unicode 17 PropList.txt White_Space, transcribed independently of the validators:
// https://www.unicode.org/Public/17.0.0/ucd/PropList.txt
const unicodeWhiteSpace = [
  0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x0085, 0x00a0, 0x1680, 0x2000, 0x2001, 0x2002,
  0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f,
  0x3000,
].map(codePoint => String.fromCodePoint(codePoint));
// FEFF is ECMAScript whitespace, retained in addition to Unicode White_Space.
const supportedWhitespace = [...unicodeWhiteSpace, '\ufeff'];
const whitespaceURIs = [
  '   ',
  '\t\r\n',
  '\u00a0\u2003\u2028\ufeff',
  ...supportedWhitespace,
  ' \t\u0085\r\n',
  '\u00a0\u2003\u0085\u2029',
  '\ufeff\u0085\ufeff',
  supportedWhitespace.join(''),
  supportedWhitespace.slice().reverse().join(''),
];
const validURIs = [
  'asset:/image.png',
  'res:/drawable/image',
  'file:///tmp/image%20name.png',
  'content://media/images/42',
  'http://example.com/image.png',
  'https://example.com/image.png?token=A%2Bb#fragment',
  ' \thttps://example.com/image.png \n',
  ...supportedWhitespace.map(space => `${space}https://example.com/image.png${space}`),
  // These are outside the approved whitespace domain and must remain meaningful.
  ...['\u200b', '\u180e', '\u2060'].flatMap(character => [character, `\u0085${character}\ufeff`]),
];

function freezePayload(value: unknown): void {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freezePayload);
    Object.freeze(value);
  }
}

const validators = [
  {
    field: 'notification.android.largeIcon',
    platform: 'android',
    notification: (asset: number | { uri: string } | string): Notification => ({
      android: { channelId: 'assets', largeIcon: asset },
    }),
    publishedURI: (notification: Notification) => notification.android?.largeIcon,
    validate: (asset: number | { uri: string } | string) =>
      validateAndroidNotification({ channelId: 'assets', largeIcon: asset }).largeIcon,
    error:
      "'notification.android.largeIcon' expected a React Native ImageResource value or a valid string URL.",
  },
  {
    field: 'BigPictureStyle.picture',
    platform: 'android',
    notification: (asset: number | { uri: string } | string): Notification => ({
      android: {
        channelId: 'assets',
        largeIcon: { uri: 'asset:/earlier-valid.png' },
        style: { type: AndroidStyle.BIGPICTURE, picture: asset },
      },
    }),
    publishedURI: (notification: Notification) => {
      const style = notification.android?.style;
      return style?.type === AndroidStyle.BIGPICTURE ? style.picture : undefined;
    },
    validate: (asset: number | { uri: string } | string) =>
      validateAndroidBigPictureStyle({ type: AndroidStyle.BIGPICTURE, picture: asset }).picture,
    error:
      "'notification.android.style' BigPictureStyle: 'picture' expected a number or object created using the 'require()' method or a valid string URL.",
  },
  {
    field: 'BigPictureStyle.largeIcon',
    platform: 'android',
    notification: (asset: number | { uri: string } | string): Notification => ({
      android: {
        channelId: 'assets',
        style: {
          type: AndroidStyle.BIGPICTURE,
          picture: { uri: 'asset:/earlier-valid.png' },
          largeIcon: asset,
        },
      },
    }),
    publishedURI: (notification: Notification) => {
      const style = notification.android?.style;
      return style?.type === AndroidStyle.BIGPICTURE ? style.largeIcon : undefined;
    },
    validate: (asset: number | { uri: string } | string) =>
      validateAndroidBigPictureStyle({
        type: AndroidStyle.BIGPICTURE,
        picture: 'https://example.com/picture.png',
        largeIcon: asset,
      }).largeIcon,
    error:
      "'notification.android.style' BigPictureStyle: 'largeIcon' expected a React Native ImageResource value or a valid string URL.",
  },
  {
    field: 'attachment.url',
    platform: 'ios',
    notification: (asset: number | { uri: string } | string): Notification => ({
      ios: {
        attachments: [
          { id: 'earlier-valid', url: { uri: 'asset:/earlier-valid.png' } },
          { id: 'target', url: asset },
        ],
      },
    }),
    publishedURI: (notification: Notification) => notification.ios?.attachments?.[1].url,
    validate: (asset: number | { uri: string } | string) =>
      validateIOSAttachment({ url: asset }).url,
    error: "'attachment.url' expected a React Native ImageResource value or a valid string URL.",
  },
];

afterEach(() => {
  jest.restoreAllMocks();
  setPlatform(Platform.OS);
});

describe.each(validators)('$field asset resolution', ({ validate, error }) => {
  describe.each([42, { uri: 'asset:/input.png' }])('input %p', asset => {
    test.each([
      undefined,
      null,
      {},
      { uri: undefined },
      { uri: null },
      { uri: '' },
      { uri: 123 },
      ...whitespaceURIs.map(uri => ({ uri })),
    ])('rejects an invalid resolved resource %p with the field validation error', resource => {
      // A broken resolver can return malformed data despite its declared RN return type.
      const resolve = jest
        .spyOn(Image, 'resolveAssetSource')
        .mockImplementation(jest.fn().mockReturnValue(resource));
      expect(() => validate(asset)).toThrow(new Error(error));
      expect(resolve).toHaveBeenCalledTimes(1);
      expect(resolve).toHaveBeenCalledWith(asset);
    });

    test('preserves the resolved URI and the original input', () => {
      const original = typeof asset === 'number' ? asset : { ...asset };
      const resource = { uri: 'asset:/resolved.png', width: 10, height: 20, scale: 2 };
      const resolve = jest.spyOn(Image, 'resolveAssetSource').mockReturnValue(resource);
      expect(validate(asset)).toBe(resource.uri);
      expect(asset).toEqual(original);
      expect(resolve).toHaveBeenCalledTimes(1);
      expect(resolve).toHaveBeenCalledWith(asset);
    });

    test('propagates resolver exceptions without masking them', () => {
      const failure = new Error('asset resolver failed');
      jest.spyOn(Image, 'resolveAssetSource').mockImplementation(() => {
        throw failure;
      });
      let caught: unknown;
      try {
        validate(asset);
      } catch (resolverError) {
        caught = resolverError;
      }
      expect(caught).toBe(failure);
    });
  });

  test.each(['', ...whitespaceURIs])(
    'rejects resolved URI %p using the actual RN resolver',
    uri => {
      const asset = Object.freeze({ uri });
      expect(resolveAssetSource(asset)).toBe(asset);
      jest.spyOn(Image, 'resolveAssetSource').mockImplementation(resolveAssetSource);
      expect(() => validate(asset)).toThrow(new Error(error));
      expect(asset.uri).toBe(uri);
    },
  );

  test.each(validURIs)(
    'preserves resolved URI %p byte-for-byte with the actual RN resolver',
    uri => {
      const asset = Object.freeze({ uri });
      expect(resolveAssetSource(asset)).toBe(asset);
      jest.spyOn(Image, 'resolveAssetSource').mockImplementation(resolveAssetSource);
      expect(validate(asset)).toBe(uri);
      expect(asset.uri).toBe(uri);
    },
  );

  test.each(whitespaceURIs)('preserves the preexisting direct string contract for %p', uri => {
    const resolve = jest.spyOn(Image, 'resolveAssetSource').mockImplementation(() => {
      throw new Error('direct strings must not be resolved');
    });
    expect(validate(uri)).toBe(uri);
    expect(resolve).not.toHaveBeenCalled();
  });

  test('rejects an unregistered asset using the actual RN resolver', () => {
    const asset = Number.MAX_SAFE_INTEGER;
    expect(resolveAssetSource(asset)).toBeNull();
    const resolve = jest.spyOn(Image, 'resolveAssetSource').mockImplementation(resolveAssetSource);
    expect(() => validate(asset)).toThrow(new Error(error));
    expect(resolve).toHaveBeenCalledWith(asset);
  });
});

describe.each(validators)(
  '$field publication boundary',
  ({ platform, notification, publishedURI, error }) => {
    beforeEach(() => {
      jest.clearAllMocks();
      setPlatform(platform);
      jest.replaceProperty(Platform, 'OS', platform === 'ios' ? 'ios' : 'android');
      jest.spyOn(Image, 'resolveAssetSource').mockImplementation(resolveAssetSource);
      mockNotifeeNativeModule.displayNotification.mockResolvedValue(undefined);
      mockNotifeeNativeModule.createTriggerNotification.mockResolvedValue(undefined);
    });

    const methods = ['displayNotification', 'createTriggerNotification'];
    const publish = (method: string, payload: Notification) => {
      const api = new NotifeeApiModule({
        version: 'test',
        nativeModuleName: 'NotifeeApiModule',
        nativeEvents: [],
      });
      return method === 'displayNotification'
        ? api.displayNotification(payload)
        : api.createTriggerNotification(payload, {
            type: TriggerType.TIMESTAMP,
            timestamp: Date.now() + 60000,
          });
    };

    describe.each(methods)('%s', method => {
      test.each(['', ...whitespaceURIs])(
        'rejects URI %p before any native invocation or partial publication',
        async uri => {
          const asset = Object.freeze({ uri });
          const payload = notification(asset);
          const original = notification({ ...asset });
          freezePayload(payload);
          expect(() => publish(method, payload)).toThrow(error);
          for (const nativeMethod of Object.values(mockNotifeeNativeModule)) {
            expect(nativeMethod).not.toHaveBeenCalled();
          }
          expect(payload).toStrictEqual(original);
          expect(asset.uri).toBe(uri);
          // A rejected resource must not leave a partially prepared publication behind.
          const validURI = `\u0085https://example.com/after-rejection.png\ufeff`;
          const validPayload = { id: 'after-rejection', ...notification({ uri: validURI }) };
          const validOriginal = { id: 'after-rejection', ...notification({ uri: validURI }) };
          freezePayload(validPayload);
          await expect(publish(method, validPayload)).resolves.toBe('after-rejection');
          for (const [name, nativeCall] of Object.entries(mockNotifeeNativeModule)) {
            expect(nativeCall).toHaveBeenCalledTimes(name === method ? 1 : 0);
          }
          const nativeMethod =
            method === 'displayNotification'
              ? mockNotifeeNativeModule.displayNotification
              : mockNotifeeNativeModule.createTriggerNotification;
          expect(publishedURI(nativeMethod.mock.calls[0][0])).toBe(validURI);
          expect(validPayload).toStrictEqual(validOriginal);
        },
      );

      test.each(whitespaceURIs)('publishes direct string URI %p unchanged', async uri => {
        const asset = uri;
        const payload = { id: 'direct-string', ...notification(asset) };
        const original = { id: 'direct-string', ...notification(asset) };
        freezePayload(payload);
        const resolve = jest.spyOn(Image, 'resolveAssetSource');
        await expect(publish(method, payload)).resolves.toBe('direct-string');
        const nativeMethod =
          method === 'displayNotification'
            ? mockNotifeeNativeModule.displayNotification
            : mockNotifeeNativeModule.createTriggerNotification;
        expect(publishedURI(nativeMethod.mock.calls[0][0])).toBe(uri);
        for (const [name, nativeCall] of Object.entries(mockNotifeeNativeModule)) {
          expect(nativeCall).toHaveBeenCalledTimes(name === method ? 1 : 0);
        }
        expect(resolve.mock.calls.some(([input]) => input === asset)).toBe(false);
        expect(payload).toStrictEqual(original);
      });

      test.each([undefined, { uri: undefined }])(
        'rejects unresolved resource %p without mutating or publishing the payload',
        resource => {
          const asset = Object.freeze({ uri: 'asset:/unresolved.png' });
          // Let earlier valid fields resolve normally, then fail the target resource.
          jest
            .spyOn(Image, 'resolveAssetSource')
            .mockImplementation(
              jest
                .fn()
                .mockImplementation(input =>
                  input === asset ? resource : resolveAssetSource(input),
                ),
            );
          const payload = notification(asset);
          const original = notification({ ...asset });
          freezePayload(payload);
          expect(() => publish(method, payload)).toThrow(error);
          expect(Image.resolveAssetSource).toHaveBeenCalledWith(asset);
          for (const nativeMethod of Object.values(mockNotifeeNativeModule)) {
            expect(nativeMethod).not.toHaveBeenCalled();
          }
          expect(payload).toStrictEqual(original);
        },
      );

      test('propagates resolver failure before native calls or partial publication', () => {
        const asset = Object.freeze({ uri: 'asset:/throws.png' });
        const failure = new Error('asset resolver failed at publication boundary');
        jest.spyOn(Image, 'resolveAssetSource').mockImplementation(input => {
          if (input === asset) throw failure;
          return resolveAssetSource(input);
        });
        const payload = notification(asset);
        const original = notification({ ...asset });
        freezePayload(payload);
        expect(() => publish(method, payload)).toThrow(failure.message);
        expect(Image.resolveAssetSource).toHaveBeenCalledWith(asset);
        for (const nativeMethod of Object.values(mockNotifeeNativeModule)) {
          expect(nativeMethod).not.toHaveBeenCalled();
        }
        expect(payload).toStrictEqual(original);
      });

      test.each(validURIs)('publishes valid URI %p exactly once without changing it', async uri => {
        const asset = Object.freeze({ uri });
        const payload = { id: 'asset-publication', ...notification(asset) };
        const original = { id: 'asset-publication', ...notification({ ...asset }) };
        freezePayload(payload);
        await expect(publish(method, payload)).resolves.toBe('asset-publication');
        const nativeMethod =
          method === 'displayNotification'
            ? mockNotifeeNativeModule.displayNotification
            : mockNotifeeNativeModule.createTriggerNotification;
        expect(nativeMethod).toHaveBeenCalledTimes(1);
        expect(publishedURI(nativeMethod.mock.calls[0][0])).toBe(uri);
        for (const [name, nativeCall] of Object.entries(mockNotifeeNativeModule)) {
          expect(nativeCall).toHaveBeenCalledTimes(name === method ? 1 : 0);
        }
        expect(payload).toStrictEqual(original);
      });
    });
  },
);

test('string resources and the explicit BigPicture largeIcon null bypass resolution', () => {
  const resolve = jest.spyOn(Image, 'resolveAssetSource').mockImplementation(() => {
    throw new Error('string and null resources must not be resolved');
  });
  const uri = 'https://example.com/image.png';
  expect(validateAndroidNotification({ channelId: 'assets', largeIcon: uri }).largeIcon).toBe(uri);
  expect(
    validateAndroidBigPictureStyle({
      type: AndroidStyle.BIGPICTURE,
      picture: uri,
      largeIcon: null,
    }),
  ).toEqual({ type: AndroidStyle.BIGPICTURE, picture: uri, largeIcon: null });
  expect(validateIOSAttachment({ url: uri, id: 'image' })).toEqual({
    url: uri,
    id: 'image',
    thumbnailHidden: false,
  });
  expect(resolve).not.toHaveBeenCalled();
});
