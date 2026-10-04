#!/usr/bin/env bash
# Builds game/target/x86_64-pc-windows-msvc/release/eldencraft.dll on Linux.
# Needs: rustup target x86_64-pc-windows-msvc, lld-link, and a Windows SDK + MSVC CRT laid out by
# `xwin splat --use-winsysroot-style` (XWIN_SYSROOT; defaults to SkyCraft's).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
XWIN_SYSROOT=${XWIN_SYSROOT:-$ROOT/../Skycraft/.install/xwin-out}
export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER=lld-link
export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS="-Clink-arg=/winsysroot:$XWIN_SYSROOT"
# C/C++ in dependencies (hudhook's MinHook and Dear ImGui).
export CC_x86_64_pc_windows_msvc=clang-cl CXX_x86_64_pc_windows_msvc=clang-cl AR_x86_64_pc_windows_msvc=llvm-lib
export CFLAGS_x86_64_pc_windows_msvc="/winsysroot $XWIN_SYSROOT" CXXFLAGS_x86_64_pc_windows_msvc="/winsysroot $XWIN_SYSROOT"
cd "$ROOT/game"
cargo build --release "$@"
ls -l "$ROOT/game/target/x86_64-pc-windows-msvc/release/eldencraft.dll"
