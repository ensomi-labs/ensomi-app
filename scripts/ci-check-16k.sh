#!/bin/sh
# Fails unless an APK's zip entries are aligned for 16 KB pages and every native library's LOAD segments are
# aligned to 16 KB. Needs ANDROID_HOME with the catalog's build-tools and NDK (scripts/ci-setup.sh).
set -eu

apk=${1:?usage: ci-check-16k.sh APK}
catalog=gradle/libs.versions.toml
pin() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$catalog"; }

"$ANDROID_HOME/build-tools/$(pin buildTools)/zipalign" -c -P 16 4 "$apk"
for readelf in "$ANDROID_HOME/ndk/$(pin ndk)"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do break; done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'lib/arm64-v8a/*' -d "$work"
for so in "$work"/lib/arm64-v8a/*.so; do
    "$readelf" -lW "$so" | awk '
      $1 == "LOAD" { count++; if ($NF != "0x4000") bad = 1 }
      END { exit !count || bad }
    ' || { echo "::error::$(basename "$so") is not aligned to 16 KB"; exit 1; }
done
