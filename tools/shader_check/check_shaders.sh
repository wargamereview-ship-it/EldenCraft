#!/usr/bin/env bash
# Compiles every HLSL entry point in game/src/gpu.rs with Wine's own d3dcompiler_47, the same way the
# game does at start-up (a shader error there turns off all block rendering, and nothing else catches it).
# Needs: wine, and the llvm-mingw toolchain from the Skycraft checkout.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${TMPDIR:-/tmp}/eldencraft-shader-check
GCC=${MINGW_GCC:-$ROOT/../Skycraft/.install/llvm-mingw/bin/x86_64-w64-mingw32-gcc}
mkdir -p "$WORK"
python3 - "$ROOT/game/src/gpu.rs" "$WORK/shaders.hlsl" <<'PY'
import sys
s = open(sys.argv[1]).read()
a = s.index('const SHADERS: &str = r#"') + len('const SHADERS: &str = r#"')
open(sys.argv[2], 'w').write(s[a:s.index('"#;', a)])
PY
"$GCC" "$ROOT/tools/shader_check/compile_shaders.c" -o "$WORK/compile_shaders.exe"
WINEPREFIX="$WORK/prefix" WINEDEBUG=-all wine "$WORK/compile_shaders.exe" "$WORK/shaders.hlsl" 2>&1 | grep -v "radv\|desktop"
