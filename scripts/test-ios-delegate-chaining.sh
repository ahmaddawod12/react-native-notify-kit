#!/usr/bin/env bash
set -euo pipefail

if [[ "${VERBOSE:-0}" == "1" ]]; then
  set -x
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/notifee-ios-delegate-chaining.XXXXXX")"

cleanup() {
  if [[ "${KEEP_TMP:-0}" == "1" ]]; then
    echo "[delegate-chaining] KEEP_TMP=1 leaving temp dir: $BUILD_DIR"
  else
    rm -rf "$BUILD_DIR"
  fi
}
trap cleanup EXIT

fail() {
  echo "[delegate-chaining] FAIL $*" >&2
  exit 1
}

HARNESS_SOURCE="$REPO_ROOT/ios/NotifeeCoreTests/NotifeeCoreDelegateChainingHarness.m"
DELEGATE_SOURCE="$REPO_ROOT/ios/NotifeeCore/NotifeeCore+UNUserNotificationCenter.m"
OUTPUT_BINARY="$BUILD_DIR/notifee-ios-delegate-chaining-tests"

[[ -f "$HARNESS_SOURCE" ]] || fail "harness source not found: $HARNESS_SOURCE"
[[ -f "$DELEGATE_SOURCE" ]] || fail "delegate source not found: $DELEGATE_SOURCE"

if ! command -v xcrun >/dev/null 2>&1; then
  fail "xcrun non disponibile; installare Xcode command line tools"
fi

SDK_ERR="$BUILD_DIR/xcrun-sdk.err"
if ! SDK_PATH="$(xcrun --sdk macosx --show-sdk-path 2>"$SDK_ERR")"; then
  cat "$SDK_ERR" >&2
  fail "SDK macOS non disponibile o Xcode license non accettata"
fi

CLANG_ERR="$BUILD_DIR/xcrun-clang.err"
if ! CLANG_PATH="$(xcrun --sdk macosx --find clang 2>"$CLANG_ERR")"; then
  cat "$CLANG_ERR" >&2
  fail "clang non disponibile tramite xcrun"
fi

COMPILE_ERR="$BUILD_DIR/clang.err"
if ! "$CLANG_PATH" \
  -fobjc-arc \
  -fblocks \
  -Werror \
  -Wall \
  -Wextra \
  -Wno-incomplete-implementation \
  -Wno-unused-parameter \
  -mmacosx-version-min=12.0 \
  -isysroot "$SDK_PATH" \
  -I "$REPO_ROOT/ios/NotifeeCore" \
  "$DELEGATE_SOURCE" \
  "$HARNESS_SOURCE" \
  -framework Foundation \
  -framework UserNotifications \
  -framework Intents \
  -o "$OUTPUT_BINARY" 2>"$COMPILE_ERR"; then
  cat "$COMPILE_ERR" >&2
  fail "compilazione harness delegate chaining fallita"
fi

if ! "$OUTPUT_BINARY"; then
  fail "harness delegate chaining fallito"
fi
