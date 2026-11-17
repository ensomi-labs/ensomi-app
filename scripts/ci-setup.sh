#!/bin/sh
# Prepares a GitHub-hosted Ubuntu runner to build the app: the catalog's SDK packages, the pinned runtime AAR and
# local.properties. Used by .github/workflows/android.yml and android-release.yml, and by the private build that
# merges this branch. The build checks the AAR again and runs the binary check; this script only fetches it.
set -eu

catalog=gradle/libs.versions.toml
pin() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$catalog"; }

: "${ANDROID_HOME:?ANDROID_HOME is not set}"
: "${RUNNER_TEMP:?RUNNER_TEMP is not set}"

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdkmanager" --licenses > /dev/null
"$sdkmanager" --install "platforms;android-$(pin compileSdk).0" "build-tools;$(pin buildTools)" "ndk;$(pin ndk)" > /dev/null

url=$(pin runtimeAarUrl)
expected=$(pin runtimeAarSha256)
if [ -z "$url" ] || [ -z "$expected" ]; then
    echo "::error::$catalog has no runtimeAarUrl or runtimeAarSha256"
    exit 1
fi
aar="$RUNNER_TEMP/ensomi-runtime.aar"
curl --fail --silent --show-error --location --retry 3 --output "$aar" "$url"
actual=$(sha256sum "$aar" | cut -d' ' -f1)
if [ "$actual" != "$expected" ]; then
    echo "::error::the runtime AAR at $url hashes to $actual, not runtimeAarSha256 $expected"
    exit 1
fi

{
    echo "sdk.dir=$ANDROID_HOME"
    echo "ensomi.runtime.aar=$aar"
} > local.properties
echo "Runtime AAR $expected from $url"
