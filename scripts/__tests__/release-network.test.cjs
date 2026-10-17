// Exercises the actual two undici majors used by the release toolchain.
// All endpoints and credentials are local fixtures; no publication occurs.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const http = require('node:http');
const https = require('node:https');
const net = require('node:net');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { createRequire } = require('node:module');

const rootRequire = createRequire(path.resolve(__dirname, '../../package.json'));
const githubRequire = createRequire(rootRequire.resolve('@semantic-release/github'));
const clients = [
  ['npm/actions', rootRequire, 6, '6.29.1'],
  ['github', githubRequire, 7, '7.30.1'],
];

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

async function close(server) {
  server.closeAllConnections?.();
  await new Promise(resolve => server.close(resolve));
}

for (const [name, loader, major, floor] of clients) {
  test(`${name}: patched undici ${major} is actually resolved`, () => {
    const version = loader('undici/package.json').version;
    const actual = version.split('.').map(Number);
    const required = floor.split('.').map(Number);
    assert.equal(actual[0], major);
    assert.ok(
      actual[1] > required[1] || (actual[1] === required[1] && actual[2] >= required[2]),
      `${name} resolves vulnerable undici ${version}; requires >=${floor} in major ${major}`,
    );
  });

  test(`${name}: HTTP, auth, redirects, errors, timeout and proxy`, async () => {
    const { fetch, ProxyAgent } = loader('undici');
    const dummyAuth = 'Bearer local-test-token';
    const destination = http.createServer((req, res) => {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ auth: req.headers.authorization ?? null, url: req.url }));
    });
    const target = await listen(destination);
    const source = http.createServer((req, res) => {
      if (req.url === '/timeout') return;
      if (req.url === '/broken') return req.socket.destroy();
      if (req.url === '/same') {
        res.writeHead(302, { Location: '/ok' });
      } else if (req.url === '/cross') {
        res.writeHead(302, { Location: `${target}/ok` });
      } else {
        res.writeHead(req.url === '/error' ? 503 : 200, { 'Content-Type': 'application/json' });
      }
      res.end(JSON.stringify({ auth: req.headers.authorization ?? null }));
    });
    const origin = await listen(source);
    const sockets = new Set();
    const proxy = http.createServer();
    let tunnels = 0;
    proxy.on('connect', (req, socket, head) => {
      const url = new URL(`http://${req.url}`);
      assert.equal(url.hostname, '127.0.0.1');
      assert.equal(Number(url.port), source.address().port);
      tunnels++;
      const upstream = net.connect(Number(url.port), '127.0.0.1', () => {
        socket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
        if (head.length) upstream.write(head);
        socket.pipe(upstream).pipe(socket);
      });
      for (const s of [socket, upstream]) {
        sockets.add(s);
        s.on('close', () => sockets.delete(s));
        s.on('error', () => {
          socket.destroy();
          upstream.destroy();
        });
      }
    });
    const proxyUrl = await listen(proxy);
    const dispatcher = new ProxyAgent(proxyUrl);
    try {
      const headers = { authorization: dummyAuth };
      assert.equal((await (await fetch(`${origin}/same`, { headers })).json()).auth, dummyAuth);
      assert.equal((await (await fetch(`${origin}/cross`, { headers })).json()).auth, null);
      const manual = await fetch(`${origin}/same`, { redirect: 'manual' });
      assert.equal(manual.status, 302);
      await manual.text();
      const failure = await fetch(`${origin}/error`);
      assert.equal(failure.status, 503);
      assert.equal(failure.ok, false);
      await failure.text();
      await assert.rejects(
        fetch(`${origin}/timeout`, { signal: AbortSignal.timeout(80) }),
        error => error.name === 'TimeoutError',
      );
      await assert.rejects(fetch(`${origin}/broken`), /fetch failed/);
      const proxied = await fetch(`${origin}/ok`, { headers, dispatcher });
      assert.equal((await proxied.json()).auth, dummyAuth);
      assert.equal(tunnels, 1);
    } finally {
      await dispatcher.close();
      for (const s of sockets) s.destroy();
      await Promise.all([close(source), close(destination), close(proxy)]);
    }
  });

  test(`${name}: TLS rejects untrusted certificates and validates configured CA`, async () => {
    const { fetch, Agent } = loader('undici');
    const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'notifykit-tls-'));
    const key = path.join(scratch, 'key.pem');
    const cert = path.join(scratch, 'cert.pem');
    execFileSync(
      'openssl',
      [
        'req',
        '-x509',
        '-newkey',
        'rsa:2048',
        '-nodes',
        '-keyout',
        key,
        '-out',
        cert,
        '-days',
        '1',
        '-subj',
        '/CN=localhost',
        '-addext',
        'subjectAltName=DNS:localhost,IP:127.0.0.1',
      ],
      { stdio: 'ignore' },
    );
    const server = https.createServer(
      { key: fs.readFileSync(key), cert: fs.readFileSync(cert) },
      (_req, res) => res.end('trusted local TLS'),
    );
    await listen(server);
    const url = `https://127.0.0.1:${server.address().port}`;
    const agent = new Agent({ connect: { ca: fs.readFileSync(cert) } });
    let customChecks = 0;
    const pinningAgent = new Agent({
      tls: {
        ca: fs.readFileSync(cert, 'utf8'),
        checkServerIdentity() {
          customChecks++;
          return new Error('local pinning rejection');
        },
      },
    });
    try {
      await assert.rejects(
        fetch(url),
        error => error.cause?.code === 'DEPTH_ZERO_SELF_SIGNED_CERT',
      );
      assert.equal(await (await fetch(url, { dispatcher: agent })).text(), 'trusted local TLS');
      await assert.rejects(
        fetch(url, { dispatcher: pinningAgent }),
        error => error.cause?.message === 'local pinning rejection',
      );
      assert.equal(customChecks, 1, 'Agent must preserve custom TLS verification');
    } finally {
      await agent.close();
      await pinningAgent.close();
      await close(server);
      fs.rmSync(scratch, { recursive: true });
    }
  });
}
