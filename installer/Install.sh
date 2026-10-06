#!/bin/sh
set -eu
package=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$(uname -s):$(uname -m)" in
    Linux:x86_64) url='@PYTHON_LINUX_URL@'; checksum='@PYTHON_LINUX_SHA@' ;;
    Darwin:arm64) url='@PYTHON_MACOS_ARM_URL@'; checksum='@PYTHON_MACOS_ARM_SHA@' ;;
    Darwin:x86_64) url='@PYTHON_MACOS_INTEL_URL@'; checksum='@PYTHON_MACOS_INTEL_SHA@' ;;
    *) printf '%s\n' 'This installer supports x86-64 Linux and Intel/Apple Silicon macOS.' >&2; exit 1 ;;
esac
runtime="$package/.python"
offline=false
for arg in "$@"; do if [ "$arg" = '--offline' ]; then offline=true; fi; done
if [ ! -x "$runtime/python/bin/python3" ]; then
    mkdir -p "$package/downloads"
    name=$(printf '%s' "$url" | sed 's|.*/||; s/%2B/+/g')
    short=$(printf '%s' "$checksum" | cut -c1-16)
    archive="$package/downloads/$short-$name"
    if [ ! -f "$archive" ]; then
        if [ "$offline" = true ]; then printf '%s\n' 'The private Python runtime is not cached. Start setup online once before using --offline.' >&2; exit 1; fi
        printf '%s\n' 'Downloading the private installer runtime...'
        curl --fail --location --proto '=https' --proto-redir '=https' --tlsv1.2 --retry 3 "$url" -o "$archive.part"
        mv "$archive.part" "$archive"
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$archive" | cut -d' ' -f1)
    else
        actual=$(shasum -a 256 "$archive" | cut -d' ' -f1)
    fi
    if [ "$actual" != "$checksum" ]; then
        rm "$archive"
        printf '%s\n' 'Installer runtime checksum mismatch. Run setup again to download a clean copy.' >&2
        exit 1
    fi
    staging=$(mktemp -d "$package/.python-new.XXXXXX")
    trap 'rm -rf "$staging"' EXIT HUP INT TERM
    tar -xzf "$archive" -C "$staging"
    # Replace only this package's private runtime, never a system Python installation.
    if [ -d "$runtime" ]; then rm -rf "$runtime"; fi
    mv "$staging" "$runtime"
    trap - EXIT HUP INT TERM
fi
printf '%s\n' 'EldenCraft private installer runtime' > "$runtime/python/INSTALLER_RUNTIME"
export ELDENCRAFT_SETUP_RUNTIME="$runtime/python" PYTHONUTF8=1
exec "$runtime/python/bin/python3" "$package/setup.py" "$@"
