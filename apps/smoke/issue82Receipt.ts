// Smoke-only verification. No native inspection or library API is introduced.
export type Session82 = {
  version: 1;
  sessionId: string;
  serial: string;
  build: string;
  bootId: string;
  candidate: string;
  apkHash: string;
  package: 'com.notifeeexample';
  uid: number;
  modulus: string;
};
export type Obligation82 = {
  attemptId: string;
  notificationId: string;
  processRunId: string;
  dispatch: 'reserved' | 'started' | 'resolved' | 'rejected';
  terminal: 'none' | 'timeout' | 'stop';
  stopResolved: boolean;
};
export type Diagnostic82 = {
  session: Session82;
  phase: 'open' | 'quiesced' | 'certified';
  epoch: string;
  consumerRunId: string;
  ledger: Obligation82[];
};
export function canonical82(value: unknown): string {
  if (Array.isArray(value)) {
    return '[' + value.map(canonical82).join(',') + ']';
  }
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    return (
      '{' +
      Object.keys(object)
        .sort()
        .map(k => JSON.stringify(k) + ':' + canonical82(object[k]))
        .join(',') +
      '}'
    );
  }
  return JSON.stringify(value);
}
export function decode82(encoded: string): string {
  if (!/^[A-Za-z0-9_-]+$/.test(encoded) || encoded.length > 16000 || encoded.length % 4 === 1) {
    throw new Error('invalid_receipt_encoding');
  }
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  let bits = 0;
  let count = 0;
  let result = '';
  for (const c of encoded) {
    bits = (bits << 6) | alphabet.indexOf(c);
    count += 6;
    if (count >= 8) {
      count -= 8;
      const byte = (bits >>> count) & 255;
      if (byte < 32 || byte > 126) {
        throw new Error('receipt_must_be_ascii_json');
      }
      result += String.fromCharCode(byte);
    }
  }
  if (count && bits & ((1 << count) - 1)) {
    throw new Error('noncanonical_receipt_encoding');
  }
  return result;
}
export function validSession82(s: Session82): boolean {
  return Boolean(
    s &&
    Object.keys(s).sort().join(',') ===
      'apkHash,bootId,build,candidate,modulus,package,serial,sessionId,uid,version' &&
    s.version === 1 &&
    typeof s.sessionId === 'string' &&
    /^[a-zA-Z0-9_-]{1,80}$/.test(s.sessionId) &&
    s.package === 'com.notifeeexample' &&
    Number.isInteger(s.uid) &&
    s.uid > 0 &&
    [s.serial, s.build].every(x => typeof x === 'string' && x.length > 0 && x.length < 300) &&
    /^[a-f0-9-]{36}$/.test(s.bootId) &&
    [s.candidate, s.apkHash].every(x => /^[a-f0-9]{64}$/.test(x)) &&
    /^[89a-f][a-f0-9]{511}$/.test(s.modulus) &&
    parseInt(s.modulus.slice(-1), 16) % 2 === 1,
  );
}
export function readDiagnostic82(raw: unknown): Diagnostic82 | null {
  try {
    if (typeof raw !== 'string' || raw.length > 12000) {
      return null;
    }
    const d: Diagnostic82 = JSON.parse(raw);
    if (
      Object.keys(d).sort().join(',') !== 'consumerRunId,epoch,ledger,phase,session' ||
      !validSession82(d.session) ||
      !['open', 'quiesced', 'certified'].includes(d.phase) ||
      typeof d.epoch !== 'string' ||
      typeof d.consumerRunId !== 'string' ||
      !Array.isArray(d.ledger) ||
      d.ledger.length > 6 ||
      new Set(d.ledger.map(x => x.attemptId)).size !== d.ledger.length ||
      !d.ledger.every(
        x =>
          /^[a-zA-Z0-9_-]+:[1-9]\d*:[a-zA-Z0-9_-]+$/.test(x.attemptId) &&
          Object.keys(x).sort().join(',') ===
            'attemptId,dispatch,notificationId,processRunId,stopResolved,terminal' &&
          typeof x.notificationId === 'string' &&
          typeof x.processRunId === 'string' &&
          x.processRunId.length > 0 &&
          ['reserved', 'started', 'resolved', 'rejected'].includes(x.dispatch) &&
          ['none', 'timeout', 'stop'].includes(x.terminal) &&
          typeof x.stopResolved === 'boolean',
      )
    ) {
      return null;
    }
    return d;
  } catch {
    return null;
  }
}
// Standard SHA256 over bounded ASCII canonical JSON, verified against Node/OpenSSL vectors.
export function sha25682(text: string): string {
  if (!/^[\x20-\x7e]*$/.test(text) || text.length > 12000) {
    throw new Error('invalid_signed_payload');
  }
  const k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];
  const h = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const bytes = [...text].map(c => c.charCodeAt(0));
  bytes.push(128);
  while (bytes.length % 64 !== 56) {
    bytes.push(0);
  }
  bytes.push(
    0,
    0,
    0,
    0,
    (text.length * 8) >>> 24,
    ((text.length * 8) >>> 16) & 255,
    ((text.length * 8) >>> 8) & 255,
    (text.length * 8) & 255,
  );
  const rotate = (n: number, by: number) => (n >>> by) | (n << (32 - by));
  for (let offset = 0; offset < bytes.length; offset += 64) {
    const w = new Array<number>(64);
    for (let i = 0; i < 16; i++) {
      w[i] =
        (bytes[offset + i * 4] << 24) |
        (bytes[offset + i * 4 + 1] << 16) |
        (bytes[offset + i * 4 + 2] << 8) |
        bytes[offset + i * 4 + 3];
    }
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15],
        b = w[i - 2];
      w[i] =
        (w[i - 16] +
          (rotate(a, 7) ^ rotate(a, 18) ^ (a >>> 3)) +
          w[i - 7] +
          (rotate(b, 17) ^ rotate(b, 19) ^ (b >>> 10))) |
        0;
    }
    let [a, b, c, d, e, f, g, q] = h;
    for (let i = 0; i < 64; i++) {
      const t =
        (q + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) |
        0;
      const u =
        ((rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      q = g;
      g = f;
      f = e;
      e = (d + t) | 0;
      d = c;
      c = b;
      b = a;
      a = (t + u) | 0;
    }
    [a, b, c, d, e, f, g, q].forEach((n, i) => {
      h[i] = (h[i] + n) | 0;
    });
  }
  return h.map(n => (n >>> 0).toString(16).padStart(8, '0')).join('');
}
export function verifyReceipt82(encoded: string, diagnostic: Diagnostic82): Record<string, any> {
  const wire = JSON.parse(decode82(encoded));
  if (
    Object.keys(wire).sort().join(',') !== 'payload,signature' ||
    typeof wire.payload !== 'string' ||
    !/^[a-f0-9]{512}$/.test(wire.signature) ||
    !validSession82(diagnostic.session)
  ) {
    throw new Error('invalid_receipt');
  }
  const signature = BigInt('0x' + wire.signature),
    modulus = BigInt('0x' + diagnostic.session.modulus);
  if (signature >= modulus) {
    throw new Error('invalid_receipt_signature');
  }
  let exponent = BigInt(65537),
    base = signature,
    decoded = BigInt(1);
  while (exponent > BigInt(0)) {
    if (exponent & BigInt(1)) {
      decoded = (decoded * base) % modulus;
    }
    base = (base * base) % modulus;
    exponent >>= BigInt(1);
  }
  const digest = '3031300d060960864801650304020105000420' + sha25682(wire.payload);
  const expected = '0001' + 'ff'.repeat(256 - 3 - digest.length / 2) + '00' + digest;
  if (decoded.toString(16).padStart(512, '0') !== expected) {
    throw new Error('invalid_receipt_signature');
  }
  const body = JSON.parse(wire.payload);
  if (
    Object.keys(body).sort().join(',') !==
      'attemptId,consumerRunId,controlNonce,correlationId,epoch,expiresAt,issuedAt,ledger,proofs,session,version' ||
    canonical82(body) !== wire.payload ||
    canonical82(body.session) !== canonical82(diagnostic.session) ||
    canonical82(body.ledger) !== canonical82(diagnostic.ledger) ||
    body.epoch !== diagnostic.epoch ||
    body.consumerRunId !== diagnostic.consumerRunId ||
    body.version !== 1 ||
    !Number.isInteger(body.issuedAt) ||
    !Number.isInteger(body.expiresAt) ||
    body.expiresAt <= Date.now() ||
    body.issuedAt > Date.now() ||
    body.expiresAt - body.issuedAt > 60000 ||
    !diagnostic.ledger.length ||
    diagnostic.ledger.some(x => x.dispatch !== 'resolved') ||
    !Array.isArray(body.proofs) ||
    body.proofs.length !== diagnostic.ledger.length
  ) {
    throw new Error('stale_or_incomplete_receipt');
  }
  diagnostic.ledger.forEach((entry, i) => {
    const p = body.proofs[i];
    if (
      p.attemptId !== entry.attemptId ||
      p.notificationId !== entry.notificationId ||
      p.processRunId !== entry.processRunId ||
      p.process?.uid !== diagnostic.session.uid ||
      p.process?.bootId !== diagnostic.session.bootId ||
      !/^[1-9]\d*$/.test(p.process?.pid) ||
      !/^[1-9]\d*$/.test(p.process?.starttime) ||
      !p.service?.identity ||
      (p.service.identityKind !== undefined &&
        !['AMS-ServiceRecord', 'app-owned-native-instance'].includes(p.service.identityKind)) ||
      (p.service.identityKind === 'app-owned-native-instance' &&
        (p.promotion !== false ||
          p.terminal !== 'denial' ||
          !/^native:[a-f0-9-]{36}:[1-9]\d*$/.test(p.service.identity))) ||
      !/^[1-9]\d*$/.test(p.service?.startId) ||
      p.service.notificationId !== entry.notificationId ||
      p.absent !== 'VERIFIED_ABSENT' ||
      !/^[a-f0-9]{64}$/.test(p.evidenceHash) ||
      !(
        (p.promotion === true &&
          ((p.terminal === 'stop' &&
            entry.terminal === 'stop' &&
            entry.stopResolved &&
            p.stopResolved === true) ||
            (p.terminal === 'timeout' && entry.terminal === 'timeout'))) ||
        (p.promotion === false &&
          p.terminal === 'denial' &&
          p.denial === 'tracked_initial_accepted' &&
          entry.terminal === 'none')
      )
    ) {
      throw new Error('uncertified_obligation');
    }
  });
  return body;
}
