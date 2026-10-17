# Dependency and release tooling maintenance

Assessment date: 17 October 2026. This describes the maintainer graph and the
package's own consumer dependencies; React Native and Expo peers have their own
dependency graphs. An installed affected version is not evidence of an exploit
through a supported NotifyKit API.

Yarn 4 workspaces orchestrate builds directly. Lerna, Nx, their Axios and
smol-toml dependencies, and the obsolete `nx/smol-toml` resolution are removed.
Keep separate compatible branches when updating transitive dependencies:

| Dependency            | Qualified version | Scope                                          |
| --------------------- | ----------------- | ---------------------------------------------- |
| undici                | 6.29.1 and 7.30.1 | npm/Actions and GitHub release tooling         |
| @grpc/grpc-js         | 1.14.6            | Google client tooling                          |
| brace-expansion       | 2.1.7 and 5.0.12  | build/test globbing; 1.1.21 remains unaffected |
| protobufjs            | 7.6.6             | Firebase/Google tooling and smoke apps         |
| pacote                | 21.5.1            | npm release tooling                            |
| firebase-admin        | 14.4.0            | maintainer FCM helper and compatibility tests  |
| @google-cloud/storage | 8.4.0             | Firebase Admin; removes the old uuid 9 chain   |

The lockfile preserves each parent's compatible range. These updates do not
update Node's built-in `fetch`; the configured GitHub release plugin imports
the separately installed undici package. Use a supported, maintained Node
release when performing a real publication.

## Public CLI

The CLI carries the stable Apache-licensed Xcode 3.0.1 parser/writer with the
upstream `node:crypto.randomUUID()` change. Provenance and licenses accompany
the vendored files. The public package no longer requires `xcode` or its old
uuid dependency. The monorepo's `xcode/uuid` resolution remains for Expo's
separate Xcode dependency; it is not a consumer security mechanism.
The React Native workspace also declares `xcode` as a development dependency
so Expo plugin compatibility tests exercise Expo's original project API without
relying on an optional public CLI dependency.

A fresh Expo consumer can still install Expo's own `xcode@3.0.1 → uuid@7.0.3`
chain, because the monorepo resolution does not transfer to that consumer.
[GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq) applies
to the installed uuid version; the examined Xcode calls use v4, which is outside
the affected APIs. This remains an Expo peer dependency finding. Expo peers can
also introduce node-forge and braces independently of NotifyKit's own graph.
Reassess when Expo updates its parents or the consumer uses affected APIs.
The fresh Expo consumer also resolves `argparse@1.0.10 → sprintf-js@1.0.3`.
[GHSA-hp3w-g68c-fv3c](https://github.com/advisories/GHSA-hp3w-g68c-fv3c) affects
untrusted precision format strings and has no official patch (latest 1.1.3 is
also affected). This is a separate consumer peer tooling risk; the monorepo's
js-yaml resolution does not remove it from fresh Expo installations. No exploit
through NotifyKit's supported API was demonstrated. Review the peer's formatter
inputs and reassess after an upstream fix; do not label the consumer audit clean.

The CLI build and prepack stage compile from fresh output and include the NSE
templates and vendor notices. The old `cli.bundle.js` is not a release input.
Pack lifecycle tests must run in a scratch copy: they generate version, native
iOS staging, server output and temporary CLI artifacts.
If packing is interrupted, the original CLI directory remains backed up under
`packages/.cli-pack-transaction`. Run `node scripts/cli-artifacts.cjs postpack`
when the generated tree is unchanged. If cleanup reports changed or missing
artifacts (including interruption between the backup and staging renames),
preserve the transaction and reconcile those files with the original backup;
do not delete the backup to bypass the check.

## Remaining advisories

These findings remain open. None is a dependency of the published package's
own consumer graph. Do not dismiss alerts or classify these versions as fixed.

| Dependency / advisory                                                                                                                                                     | Reachability and treatment                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| node-forge 1.4.0 / [GHSA-86w9-cpqp-85rv](https://github.com/advisories/GHSA-86w9-cpqp-85rv)                                                                               | No official patch. Expo smoke CLI/code signing can verify RSA PKCS#1 v1.5 signatures and certificates. The attack requires crafted signing material and a suitable low-exponent RSA key. EAS configuration and incoming `expo-expect-signature` requests can activate signing paths; absence of explicit code-signing fields does not prove them disabled. Use trusted keys/certificates and a localhost development server. Avoid verification of untrusted signing material. These operational limits do not repair the vulnerable verifier. |
| @grpc/grpc-js 1.9.16 / [GHSA-m9gg-hp2v-232j](https://github.com/advisories/GHSA-m9gg-hp2v-232j), [GHSA-f596-whhp-79r4](https://github.com/advisories/GHSA-f596-whhp-79r4) | Private smoke RN Firebase 21.14 → Firebase 11.3.1 → Firestore 4.7.8 requires `~1.9.0`, which has no compatible patch. The findings concern gRPC server authentication/error handling; smoke uses mobile app/messaging, and NotifyKit's Server SDK has no gRPC runtime dependency. Do not override Firestore's minor range or use this branch for a gRPC server. Even the current Firestore parent retains that range.                                                                                                                          |
| braces 3.0.3 / [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)                                                                                   | No official patch. Deeply nested untrusted patterns can exhaust the tooling stack through micromatch/Jest/Metro/lint. Run repository tooling on reviewed inputs in isolated jobs; do not expose glob expansion as an untrusted-input service.                                                                                                                                                                                                                                                                                                  |

Reassess these findings when a leaf patch or compatible parent is released,
before publication, and whenever smoke signing, untrusted glob inputs, or
server gRPC usage changes. Re-run `yarn npm audit -A -R --json` and inspect
advisory URLs and upstream releases: deprecations are reported separately from
active security advisories. The audit intentionally remains nonzero while
these affected versions remain installed.

## Qualification

Run `yarn test:build-tooling`, `yarn test:release-network`, CLI tests, package
Jest/Expo plugin tests, Server SDK tests, smoke Jest tests, TypeScript/lint,
native formatting and Android JUnit using JDK 21. Compare native source hashes
before and after tooling work. No fresh device qualification is implied by
host tests when native production sources are unchanged.

Before publishing, build and pack a clean scratch checkout and repeat after
planting a stale CLI bundle. Compare complete tarball hashes, install the real
tarball into fresh npm and Yarn consumers, execute `init-nse` twice, and verify
the extension, exports, types, plugin, server SDK and native cores. Inspect the
tarball for unintended files and secrets. Use local fixtures and dummy tokens
for release networking tests; a real authenticated publication is a separate
operation.

The release configuration uses `v${version}` to recognize the repository's
existing public tags, including `v10.8.1`. A different tag namespace would make
semantic-release calculate a first release. A credential-free dry run may use
copied local Git metadata and only commit analysis/release notes plugins;
authentication and publication hooks remain part of the separate release gate.

Upstream patch references: [undici 6.29.1](https://github.com/nodejs/undici/releases/tag/v6.29.1),
[undici 7.30.1](https://github.com/nodejs/undici/releases/tag/v7.30.1).
