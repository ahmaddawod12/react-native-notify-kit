# Cordova Xcode compatibility layer

The runtime parser and project writer are vendored from the published, stable
`xcode@3.0.1` npm package, Apache Cordova's
[cordova-node-xcode](https://github.com/apache/cordova-node-xcode). They are not a
new parser. `lib/parser/pbxproj.js` and its PEG grammar are copied unchanged.
The worker parser (`parseJob.js`) is retained alongside the synchronous parser.

The npm tarball has integrity
`sha512-kCz5k7J7XbJtjABOvkc5lJmkiDh8VhjVCGNiqdKCscmVpdVUpEAyXv1xmCLkQJ5dsHqx3IPO4XW+NTDhU/fatA==`.
`UPSTREAM-SHA256.json` records original file hashes, before the local patch.
Apache's LICENSE, NOTICE, AUTHORS and file headers are preserved and shipped.

Only `lib/pbxProject.js` differs from that release: two substitutions change its
`uuid` import and `v4()` call to `node:crypto` and `randomUUID()`, and an in-file
modification notice identifies this local change. This is the change already
merged upstream as
[`1edf7059d347beb0e339d1cb4741581352b43138`](https://github.com/apache/cordova-node-xcode/commit/1edf7059d347beb0e339d1cb4741581352b43138).
The UUID formatting and collision check are unchanged. Node >=22 is required by
this CLI. `simple-plist` remains an explicit dependency: the full compatibility
layer also supports reading data-model metadata; dropping that dependency while
retaining those methods would leave a hidden missing import.

This avoids shipping the unpublished `xcode/uuid` override as an assumed
consumer contract. It also avoids migrating real Xcode parsing to an alpha API.
The three compatibility workarounds in `src/lib/patchXcodeProject.ts` remain.

To update, obtain a reviewed stable upstream tarball, verify its registry
integrity, replace these files and their original hashes, and re-evaluate the
UUID patch. Run CLI fixture, structural preservation, worker parsing, NSE
idempotence and consumer tarball tests on Node 22 and 24. Compare the CocoaPods
project structure before removing existing compatibility workarounds.
