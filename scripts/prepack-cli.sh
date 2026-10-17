#!/usr/bin/env bash
# Kept as the existing prepack entrypoint; all output comes from a fresh build.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec node "$SCRIPT_DIR/cli-artifacts.cjs" prepack
