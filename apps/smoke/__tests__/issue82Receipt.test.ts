import { canonical82, decode82, sha25682, verifyReceipt82 } from '../issue82Receipt';
import type { Diagnostic82 } from '../issue82Receipt';
const crypto = jest.requireActual('node:crypto');
const Buffer82 = jest.requireActual('node:buffer').Buffer;
test('SHA256 bounded ASCII vectors agree with independent Node implementation', () => {
  for (const length of [0, 3, 55, 56, 63, 64, 65, 11999]) {
    const s = 'a'.repeat(length);
    expect(sha25682(s)).toBe(crypto.createHash('sha256').update(s).digest('hex'));
  }
  expect(() => sha25682('é')).toThrow();
});
test('RSA2048 padding, canonical signed JSON and malformed transport fail closed', () => {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const modulus = Buffer82.from(keys.publicKey.export({ format: 'jwk' }).n, 'base64url').toString(
    'hex',
  );
  const d: Diagnostic82 = {
    session: {
      version: 1,
      sessionId: 's',
      serial: 'dev',
      build: 'android16',
      bootId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      candidate: 'a'.repeat(64),
      apkHash: 'b'.repeat(64),
      package: 'com.notifeeexample',
      uid: 1,
      modulus,
    },
    phase: 'quiesced',
    epoch: 'e',
    consumerRunId: 'p',
    ledger: [
      {
        attemptId: 'run:1:n',
        notificationId: 'fg',
        processRunId: 'p',
        dispatch: 'resolved',
        terminal: 'stop',
        stopResolved: true,
      },
    ],
  };
  const body = {
    version: 1,
    session: d.session,
    epoch: 'e',
    consumerRunId: 'p',
    ledger: d.ledger,
    correlationId: 'run',
    attemptId: 'run:1:n',
    controlNonce: 'n',
    issuedAt: Date.now(),
    expiresAt: Date.now() + 10000,
    proofs: [
      {
        attemptId: 'run:1:n',
        notificationId: 'fg',
        processRunId: 'p',
        process: { pid: '1', starttime: '2', bootId: d.session.bootId, uid: 1 },
        service: { identity: 'a', startId: '1', notificationId: 'fg' },
        promotion: true,
        terminal: 'stop',
        stopResolved: true,
        absent: 'VERIFIED_ABSENT',
        evidenceHash: 'c'.repeat(64),
      },
    ],
  };
  const encode = (
    payload: string,
    signature = crypto.sign('sha256', Buffer82.from(payload), keys.privateKey).toString('hex'),
  ) => Buffer82.from(canonical82({ payload, signature })).toString('base64url');
  expect(verifyReceipt82(encode(canonical82(body)), d).attemptId).toBe('run:1:n');
  for (const wire of [
    encode(canonical82(body), '00'.repeat(256)),
    encode(canonical82(body), 'ff'.repeat(256)),
    encode(JSON.stringify(body)),
    encode(canonical82({ ...body, unknown: true })),
    encode(canonical82(body)).slice(0, 20),
  ]) {
    expect(() => verifyReceipt82(wire, d)).toThrow();
  }
  expect(() => decode82('@@@')).toThrow();
});
