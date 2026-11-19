---
commit: 881941931bb03b6f000b57e42760b270b9cd212b
---

# Android CI and release

## Public demo: the current process

The Android play demo is built on the owner's Mac from a committed tree, then signed by the owner with one
release key made by hand and published as a GitHub release of `ensomi-labs/ensomi-app`; 0.2.2 (code 7) was the last built
that way, and 0.2.3 (code 8) and 0.3.0 (code 9) were built and signed by [Automation](#automation). The current version is
`versionName` **0.3.1**, `versionCode` **10**, owned by `gradle/libs.versions.toml`, adding library song search and
swipe-left/long-press removal with Undo, a gameplay setting for hold tail judgement (off by default) with 100 ms
hold-body combo ticks, visual hold-tail shortening (`0–80 ms`), and capless hold-tail rendering. Swift's `v0.1.0` stays separate. The build produces an unsigned,
R8-optimized arm64-v8a APK, with JNA keep rules and the runtime AAR's UniFFI keep rules. The runtime is the local AAR
whose SHA-256 is `45fa0fef0c362b36c3c5e5d5fc703e8d430eb233ffc788455f48c08a2831aa58`, pinned as `runtimeAarSha256` in
`gradle/libs.versions.toml`.
It is built from runtime commit `1424a70` (branch `agy/hold-tail-combo`, adding optional `tailJudgement` and 100 ms
`holdTicks` body combo to `Ruleset` on top of the `c3705e2` `ensomi-audio`/`symphonia` KleidiAI-free Android build; the
`android-aar-1424a70` pre-release asset is pending upload once its PR merges).
Keep the exact unstripped
`libensomi_runtime_ffi.so` per release alongside the AAR, its SHA-256 and R8 mapping for symbolizing
reported offsets; rebuilding later is not a substitute for retaining that binary. Diagnostics
`runtime.identity` is the SHA-256 of the AAR Gradle actually links.

0.1.3 separates resumable transfer, verification, commit and guarded first open; persists capabilities;
and adds durable install events, exit recovery and a launcher Diagnostics shortcut. The guard now covers first
inference; cold shares wait for readiness. Crash projection 2 prioritizes the crashing thread, reads up to 8 MiB
in memory and exports up to 512 KiB of redacted evidence. Install over
0.1.2 without uninstalling so earlier exit records survive. The candidate has not run on a CPU
with SME, and the suspected affected-phone fix still needs a tester rerun.

0.2.2 keeps the stars 0.2.1 offers, 2.0 to 5.9, and shows what was made. Each chart is measured as it is made, and
the chart-ready card, the game's HUD and the results show the star it came out at, with the star asked for beside
it when they differ. An experiment on twelve songs with two starting seeds (ensomi-runtime `codex/star-reach` at
`6dfc2a2`; measured stars on a computer, nobody played a chart) found that a first chart for a target from 2.5 to 5.5
stars misses by a median of 0.43 stars. It also found that every request at or below 2.125 stars makes one chart for
a song and seed, and every request at or above 5.875 another, measuring about 2.5 and 5.3 stars at the centre: stars
beyond this range would ask for the same charts as its ends, which is why the range stays. An install over 0.2.1 keeps the charts stored for each song; they have
no measured star and show the star asked for alone. That rests on a unit test reading a record in 0.2.1's shape; the
install itself has not been tried.

Precise difficulty is a switch on the song's screen, off by default: it makes up to six charts, asking only for stars
the slider offers, and keeps the one closest to the star asked for. In the same experiment the search brought the
median miss from 0.43 stars to 0.08, with about two thirds of its charts within 0.15 stars; that was measured with
requests allowed three stars from the target, and again, no worse, with requests held to 2 to 6, not with the
slider's own 2.0 to 5.9 that the app uses.

0.2.2 also puts back the library fix that 0.2.1 lost. 0.2.1 was built with `ensomi.runtime.aar` pointing at an older
AAR (SHA-256 `16c647da00a050f3cd13a33b7a1124f467c152819b36139a6186cd195f220880`, runtime `b12bb0f`) instead of the one this
document required then (`468a11aa3f53db041b44478fbf8d6e49d57411b72c5d01594f1c6b05a3ec0738`). Nothing compared the two: the build
hashed whatever it linked into `BuildConfig.RUNTIME_AAR_SHA256` and went on. The older library's ONNX Runtime contains
KleidiAI's SME2 kernels. On a CPU that has SME and not SME2 it executes `ptrue pn9.b` straight after `smstart` while the
model opens, and the app dies with SIGILL; one user's diagnostics show three such crashes of 0.2.1. 0.1.0, 0.1.1, 0.1.2
and 0.2.1 package that same library; 0.1.3 and 0.2.0 carry the fixed one.

For the AAR that 0.2.2 links, this ran on the owner's Mac on 2026-11-10 (Codex job
`20261110-225720-android-library-gate`): the runtime's build passed the checker, `scripts/check-no-kleidiai.py`, at both
of its gate points; the checker fails on the 0.2.1 library (22 kernel strings, 495 instruction lines) and passes this
one with zero findings; 151 workspace tests and 46 tests with the `sync` feature passed. The ONNX Runtime linked was an
existing build directory configured with `onnxruntime_USE_KLEIDIAI=OFF`, not compiled afresh in that job, so the verdict
rests on the checker, not on the flag. Nothing ran on a phone.

The app's build now refuses any other AAR and checks the native code it packages. Gradle stops every build type, the
unit tests and lint when the file `ensomi.runtime.aar` selects does not hash to the pin, at configuration and again
before `preBuild`; nothing turns that off. It runs the same checker on the library inside the AAR before `preBuild` and
on the library inside every APK an assembly leaves, and any exit but 0 fails the build (README, Build requirements).
The checker finds KleidiAI's kernels and SME code; a pass does not show that the library runs on every CPU. The app
does not refuse by CPU features on the device: with the pin and the check it cannot contain the affected library, so
such a block could not be reached, nor tested without an affected phone.

Verified on the owner's Mac on 2026-11-10 at `e59aa3a` (Codex job `20261110-231554-codex-1110-1415`): the guards ran in
two consecutive full builds and passed with the pinned AAR; the 0.2.1 AAR was stopped by its hash at configuration, a
file replaced at the same path was stopped, and with the pin set to the old hash in a throwaway tree the checker
stopped the old AAR and the released 0.2.1 APK, with 22 kernel strings and 495 forbidden instruction lines. Not
verified: no phone with SME and without SME2 is recorded as having run 0.2.2.

The same Mac build at `e59aa3a` assembled debug, checks, release and the instrumentation APK, passed 609 unit tests
with no lint finding new against 0.2.1, and launched the debug app on the 16 KB emulator. The owner signed the release
APK on 2026-11-10; it is published as `android-v0.2.2`, from that commit. The signed build with the owner's private
additions was installed over 0.2.1 on the owner's phone, keeping its data, and launched once without a crash; that phone
has no SME, and the launch did not open the model. The owner reports having tested the build; what was tested is not
recorded here. No chart generated on an Android device with this build is on record.

Known in 0.2.2, as in 0.2.1: a section's star target at the top of its slider (5.9) is judged out of range and blocks
Make chart; 5.8 works. Its fix is deferred.

Up to 0.2.2 the owner kept the keystore outside all repositories and signed separately with `apksigner`. The
workflows in [Automation](#automation) replace that once the owner has done the setup in
[section 9](#9-order-of-work); none of them has run yet.

The initial demo was 0.1.0 (code 1, `android-v0.1.0`). The 0.1.1 patch replaces the launcher icon with the website
artwork; this build remains unsigned until the owner signs it. No tag or publication is part of the icon patch.

## Public recognition

Ensomi never operates another app. It uses no Accessibility service, reads no other app's screen, and makes no taps for the person. The person recognises the song, downloads it and shares it to Ensomi themselves; Ensomi explains each step, then waits for the share.

Listen & Play is included in the public APK. Relative to the preceding release manifest it adds `RECORD_AUDIO`
(for local alignment), `READ_MEDIA_AUDIO` (for the downloaded MP3), the package query for `com.netease.cloudmusic`
and the exported `ACTION_SEND` text share target. It adds no foreground service or special storage access.
Release uses microphone capture, autoplay off, 3.5 stars and zero ambient offset; debug controls and external options
are unavailable. Microphone samples are neither saved nor uploaded. The optional public song short-link lookup and
the pinned model download are the network activity. Local recognition traces and caches are bounded; see
[recognition.md](recognition.md). Signing and publication follow [section 5](#5-signing).

## Automation

The workflows are on this branch: `.github/workflows/android.yml` builds and checks every push, and
`android-release.yml` builds, signs and publishes a tagged release. They depend on GitHub settings only an organisation
owner can make and on the runtime AAR being downloadable ([section 9](#9-order-of-work)); until then `android.yml`
fails at setup and no release can be signed. Builds of the phone kit, arm64 emulator runs and the phone checks stay on
the Mac.

The organisation is on GitHub Free. Everything below works there because this repository is public; `commons` is
private, and Free gives a private repository no environments, so nothing holding the key runs there.

| Topic | Decision |
| --- | --- |
| CI | `android.yml` on `ubuntu-24.04`: every build type, the `androidTest` APK, JVM tests, lint, a 16 KB check. No secrets, no APK artifacts. |
| Runtime AAR | The pinned AAR as a release asset of `ensomi-labs/runtime`, fetched by `scripts/ci-setup.sh` and checked by the build's existing pin. Local builds keep naming the file in `local.properties`. |
| Emulators | No hosted runner can run an arm64 image, so arm64 AVDs stay on the Mac. No self-hosted runner, no device farm. |
| Phones | The human-run kit, built on the Mac from a commit whose CI passed. |
| Release | An annotated `android-v*` tag, pushed by a signer, builds and signs the APK and makes a draft release; a releaser's approval publishes it. |
| Signing | One key, held in the `android-signing` environment, used only by `apksigner` in a job that runs no repository code. |
| Private APK | Built by `commons`' own workflow from the same tag there, signed by the same job here through a GitHub App token scoped to `commons`, attached to a release in `commons`. |
| Coordinated release | Runtime and model first; one commit moves the app's pins; the app goes last. |

## 1. CI on `dev/android`

CodeQL’s default setup is off, because this branch has no workflows and Kotlin cannot be analysed without a build,
which needs the runtime AAR. An advanced workflow comes with the published AAR.


**Where the file lives.** `.github/workflows/android.yml` on `dev/android`, the default branch. "Each workflow run will use
the version of the workflow that is present in the associated commit SHA or Git ref of the event", so pushes, pull requests
and tags each run the workflow of their own commit.

**Triggers.** Pushes to `dev/android`, `claude/**` and `codex/**`, and pull requests into `dev/android`, unless they touch only
docs. Agent branches are in so that an agent without the toolchain gets a build from a push; Swift branches have no
`android.yml` and start nothing. No required check: `dev/android` keeps taking direct pushes.

**What runs.** One job: `scripts/ci-setup.sh` (the catalog's SDK packages, the pinned AAR, `local.properties`),
`assembleDebug assembleDebugAndroidTest assembleChecks assembleRelease testDebugUnitTest lint`, the 16 KB check
(`scripts/ci-check-16k.sh`), and a check that `scripts/phone-checks.sh` and `ModelSource.kt` pin the same model.

**Runner and cache.** `ubuntu-24.04`: 4 CPUs, 16 GB, free on a public repository, with the pinned SDK packages already in its
image; the workflow still installs the catalog's pins, so they stay written in one place. `setup-gradle` uses `cache-provider:
basic` (MIT), not its proprietary default. Only pushes to `dev/android` write the cache. A new agent branch builds cold once;
a release never uses the cache.

**Forks and secrets.** The workflow has no secrets and runs with `contents: read`. A fork's pull request gets a read-only token
and no secrets, and first-time contributors wait for approval. `pull_request_target` and `workflow_run` are never used. No APK
goes into artifacts: anyone signed in to GitHub can read a public repository's, which would distribute `checks`.

**Beside `dev/swift`'s CI.** `ci.yml` lives only on `dev/swift` and the Android workflows only on `dev/android`, so neither sees
the other's pushes.

## 2. The runtime AAR

This blocks all CI: the app does not compile without the runtime's classes.

**The app's side.** `runtimeAarUrl` in `gradle/libs.versions.toml` names where CI downloads the file that
`runtimeAarSha256` pins. `scripts/ci-setup.sh` downloads it with no token, compares its SHA-256 and writes
`local.properties`; Gradle then checks the hash again and runs the binary check, exactly as on the Mac. Gradle never reads
the URL, so a local build cannot pick up a downloaded file by accident, and moving the pin means changing both lines in
one commit.

| Option | Verdict |
| --- | --- |
| Runtime release asset, downloaded by CI, checked by the existing pin | **Chosen.** No token for public reads, no runtime toolchain in the app's CI, no change to Gradle. |
| The same asset resolved by Gradle as an Ivy module with `verification-metadata.xml` | Not now: a second pin beside `runtimeAarSha256`, and a dependency path no build has tried through AGP. |
| GitHub Packages (Maven) | No: "You need an access token to publish, install, and delete private, internal, and public packages", forks' builds included. |
| Maven Central | Not now: namespace verification and signing for one consumer. |
| Build in the app's CI from a pinned commit | No: Rust, cargo-ndk and an ONNX Runtime source build in every cold run. |

**The runtime's side, first.** A prerelease `android-aar-ecc4c36` of `ensomi-labs/runtime` carrying the AAR 0.2.2 links,
`ensomi-runtime-ecc4c36.aar` (SHA-256 `606034d0…`), with the unstripped library and `SHA256SUMS` beside it, created from
the owner's approved file with `--prerelease --latest=false --target ecc4c36f81bff4d411db0e3458bffe2707d6b27f`. No runtime
workflow fires, and `main`, npm and `Package.swift` stay untouched. It has no CI build or attestation, so it serves CI and
releases built from the same pinned file, not a claim about the runtime's release process.

**Then a release.** Later, a `vX.Y.Z` runtime release carries the AAR, built and attested by the runtime's CI. That asks of the
runtime an `android` job in `ci.yml` with `scripts/build-android.sh` made portable to `ubuntu-24.04`, `release.yml` attaching
and attesting the AAR, JNA's rules in `android/consumer-rules.pro`, and notices for the Rust crates in the AAR.

## 3. Emulators and devices

| Option | Verdict | Why |
| --- | --- | --- |
| arm64 image on `ubuntu-24.04` | No | The emulator refuses an arm64 AVD of API 28 or later on an x86_64 host: "System image must match the host architecture" (`main-emulator.cpp`). |
| arm64 image on `ubuntu-24.04-arm` | No | The SDK repository has no Linux arm64 emulator: its hosts are Linux x64, macOS x64 and aarch64, Windows x64. |
| arm64 image on hosted Apple silicon | No | GitHub: "Nested-virtualization is not supported due to the limitation of Apple's Virtualization Framework." |
| x86_64 image on `ubuntu-24.04` with KVM | **Try once** | GitHub's Linux runners "support hardware acceleration for Android SDK tools". The API 34 to 37.0 x86_64 images declare `arm64-v8a` through `libndk_translation.so` in their `build.prop`, so this arm64-only APK should install. API 33's does not. |
| Self-hosted Mac runner | No | GitHub: they "should almost never be used for public repositories". The Mac holds private data; a fork's pull request must not reach it. |
| Firebase Test Lab | No | Deprecated, shut down on 2027-09-30; its replacement requires billing. |

**The experiment, not yet run.** On a branch, with the API 36 x86_64 image: install the debug APK, read `EnsomiDiagnostics`, run
`RuntimeCheck`; then launch the release APK, which catches what R8 broke. It says nothing about timing, and whether JNA and ONNX
Runtime run under translation is unknown. Keep it if a week of runs is stable; otherwise drop it and say why here.

| Where | What runs | When |
| --- | --- | --- |
| `ubuntu-24.04` | Builds, JVM tests, lint, 16 KB check | Every push |
| Mac, arm64 AVD `ensomi-36-16k` | Screens, flows, real 16 KB loading, the phone checks on an emulator, the release APK's smoke test | A build brief through Astra |
| Phones | Timing, audio, HyperOS, the checklist | A person, before a release |

**The kit.** Built on the Mac from a commit whose CI passed, without the override, by `scripts/make-phone-kit.sh`, which should
refuse a dirty tree and record the commit and pins. Not from CI: an artifact would be public, CI's debug key changes each run,
and the model, click tracks and songs need the Mac anyway.

## 4. What a release is

One signed arm64-v8a APK built by CI from a tag, on a GitHub release with its SHA-256 and `mapping.txt`. The model is never in it.
No AAB until a store is planned.

| Channel | What | Who | When |
| --- | --- | --- | --- |
| Internal | A draft release | Write access ("Only users with push access will receive listings for draft releases") | After signing |
| Public | That draft, published, not rebuilt | Anyone | A releaser's approval |
| Stores | An AAB and a listing | — | Postponed |

**Tags.** Annotated `android-vX.Y.Z`; bare `v*` stays Swift's. The version numbers are independent. Android releases have been
marked Latest since 0.2.0, and `publish` keeps doing so.

**Versions.** `appVersionName` and `appVersionCode` live in the catalog. `versionCode` goes up by one for every tag and is never
reused. `build` fails unless the tag is annotated, is `android-v<appVersionName>`, is on `dev/android`, and its code is above
every other `android-v*` tag's.

**Changelog.** The annotated tag's message, plus the commit, the run, the runtime pin, the APK's SHA-256 and the certificate.
A releaser may edit the draft's title and text before approving. `--generate-notes` would compare histories that share nothing.

**Notices.** The Notices screen has the model's and ONNX Runtime's. A public APK also needs AndroidX, Compose, Kotlin, kotlinx, JNA
(under Apache-2.0), libc++ and the Rust crates: a generated list for the Kotlin side, the AAR for the native side.

**R8 (proposal; already enabled for the public demo).** `release` is unsigned and R8-optimized. Google: "You should always enable optimization for your app's release build"; on
AGP 9.3 and later that is `optimization { enable = true }`. Add JNA's rules, which neither its AAR nor the runtime's consumer rules
carry: `-dontwarn java.awt.**`, `-keep class com.sun.jna.** { *; }`, `-keep class * extends com.sun.jna.** { *; }`. `checks` has no R8,
so the release APK gets its own smoke test on the Mac AVD and by hand on both devices.

**Mapping, symbols, 16 KB.** `mapping.txt` stays on the release. With no crash service nothing is uploaded; the exact unstripped native library and its GNU build ID are retained per release for symbolizing. CI checks zip alignment and every library's `LOAD` segments, and `sign`
checks the zip again.

**The workflow.** `android-release.yml`, on a push of `android-v*`:

1. `build`, no secrets: the tag and version checks, `assembleRelease` without the Gradle cache, the 16 KB check; the unsigned
   APK, `mapping.txt` and the notes kept as an artifact for a day. That artifact is readable by anyone signed in to GitHub; it
   holds the unsigned form of what will be public.
2. `sign`, environment `android-signing`, no checkout and no Gradle: `apksigner sign` with schemes v2 and v3, then checks
   that exactly one signer with the release certificate signed it with both schemes, that it is aligned, and that its zip
   entries are the unsigned APK's; attests it; creates the draft release with the APK, its `.sha256` and `mapping.txt`; then
   the private APK ([section 5](#5-signing)). The keystore is written to the runner's temporary directory and removed.
3. `publish`, environment `android-release`: after a releaser approves, checks that the draft still holds exactly the APK
   `sign` made, then publishes it.

## 5. Signing

**One key** for internal, public and later store builds, and for the private APK, so every build upgrades every other. It is
the key 0.1.0 to 0.2.2 were signed with; another one would make every user uninstall, losing the library and the model.
`checks` stays on the debug key and is never distributed.

Release certificate SHA-256: `92188ba6c6abbe64a8f2494a756835cec2ab22be3e40c6a21ae16f62ce4f0c3b` (`CERT_SHA256` in
`android-release.yml`; `sign` refuses any APK that verifies under another).

**Who does what.**

| Role | Who | How |
| --- | --- | --- |
| Signer, team `android-signers` | Members trusted to cut a release | Pushing an annotated `android-v*` tag starts `build` and `sign` with no further prompt. Only this team may create, move or delete such tags. |
| Releaser, team `android-releasers` | Members trusted to publish | Approving `publish` in the run. A releaser may approve a run they started; with one releaser, a second person cannot be required yet. |
| Organisation owner | — | The settings below, the key's secrets, the GitHub App. |

The signer's authorisation is the tag push itself, under the organisation's required two-factor authentication: nobody types
the keystore password. Approving is a person's act: an agent never approves, pushes a release tag or handles the key, even
with a token that could.

**Settings (organisation owner).**

| Where | Setting |
| --- | --- |
| Organisation teams | `android-signers` with write access to this repository; `android-releasers` with write access (its members review `.github/` as code owners). |
| Tag ruleset | Target `refs/tags/android-v*`; restrict creations, updates and deletions; bypass: `android-signers` only. |
| Environment `android-signing` | Deployment branches and tags: tags matching `android-v*` only. No required reviewers. Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` only if the key's password differs; `COMMONS_APP_PRIVATE_KEY`. Variable: `COMMONS_APP_CLIENT_ID`. |
| Environment `android-release` | Deployment branches and tags: tags matching `android-v*` only. Required reviewers: `android-releasers`. Prevent self-review off. Allow administrators to bypass: off. No secrets. |
| Branch ruleset on `dev/android` | Require a pull request with code owner review, so `.github/CODEOWNERS` holds: the workflow at a tag decides what `sign` does with the key. |
| GitHub App | Owned by the organisation, installed on `commons` only, with repository permissions Actions: read and Contents: read and write. Its client ID and a private key go to `android-signing`. |
| Releases | Immutable releases, once the first release from this workflow is published (repository-wide, so Swift's too). |

**Moving the key, once,** with no agent running on that machine. Set the secrets from the keystore file and typed passwords:

```sh
base64 -i ensomi-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env android-signing -R ensomi-labs/ensomi-app
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-signing -R ensomi-labs/ensomi-app
```

GitHub secrets cannot be read back, so keep two copies away from GitHub (a password manager, an encrypted drive), then delete
the keystore from the Mac: an agent can read any file there.

**The private APK.** `commons` holds the private source on a branch that merges `dev/android`, with its own
`.github/workflows/android-private.yml`: on a push of the same `android-v*` tag there, it builds the unsigned private APK with
no secrets and keeps it as a private artifact for a day. Push that tag in `commons` before the tag here. `sign` then, with the
App's token, checks that the `commons` tag contains this release's commit, waits up to an hour for that build, checks that the
private APK has the public one's package and version, signs and checks it like the public one, and attaches it to a release of
`commons`. It never becomes an artifact, release or attestation of this repository, and the job prints nothing about its
contents. With no such tag in `commons`, `sign` skips it.

**What this protects and what it does not.** A compromised Gradle plugin in `build` could alter an APK but cannot reach the
key. Anyone with write access can change a workflow on a branch, but only a signer's tag reaches `android-signing`, and code
owner review guards what that tag runs. A signer is trusted with the key's use; a releaser with what is published. An owner can
change any of these settings.

**Loss and rotation.** A lost key ends upgrades. A held key can be retired with `apksigner rotate`; rotated keys work from Android 13,
this app's minSdk. Rotation does not undo a leak: a device that has not updated still trusts the old key.

**Later.** For Play, give Google a copy of this key at enrolment, with a new upload key: a Google-generated key would stop Play and
GitHub builds upgrading each other. Android developer verification covers "all apps on certified Android devices" in 2027 (direct
sideloads are not covered yet, ADB is exempt), so a public APK will need the package and this key registered.

## 6. Getting a build to the testers

Testers with write access see the draft. GreatFire's public probes, none from the teammate's network: `github.com` is
intermittent (42 of 62 tests disturbed), `release-assets.githubusercontent.com`, where downloads redirect, connected in 5 of 5 (last
on 2026-10-10), `huggingface.co` is blocked (15 of 15). At the first draft the teammate tries one download. If it fails, the owner sends the APK
zipped over whatever the two already use, and the teammate checks the SHA-256. Behind the block the model comes from the kit by
Install from files.

**On HyperOS.** `adb install -r`, tapping `继续安装` within 10 s ([the runbook](phone-checks.md)), or open the APK in the file manager and
allow that app to install unknown apps; nobody has recorded those dialogs with a signed build. From a debug build,
`adb uninstall io.ensomi.android` first, which also removes the model, settings and library.

## 7. The model pin and coordinated releases

**Pins.** The runtime: the catalog and `verification-metadata.xml`. The model: `ModelSource.kt`; `scripts/phone-checks.sh` restates its
hash, which CI checks.

**What an update does.** On the first start after an upgrade the app downloads the pinned tag over the old install, which stays in use
until the new one is in place. Where Hugging Face is blocked the download waits, and Install from files takes the new package. Stored
charts stay. The same seed makes the same chart only on the same model and runtime, so a release that moves either says
so.

**Order,** as ensomi-model's `docs/ecosystem.md` says: a release or upload comes before those who take it.

1. The runtime and the model, in that page's order for the change. A package's `min_runtime` must not exceed the runtime the app pins.
   The runtime's release carries the AAR.
2. The app, last: one commit moves every pin; CI; a kit with the new package; the phone checks; a tag.

The Swift app pins independently. That page still calls this repository "the macOS and iOS app"; it should add Android. A mirror of the
model for China is a new host for the weights, so it goes through the project's review of weights releases, not this document.

## 8. Provenance and supply chain

Worth it for a small team, at a few lines each: actions pinned by SHA; wrapper validation, which `setup-gradle` does; the AAR's SHA-256;
`actions/attest` on the APK and, in the runtime's release, on the AAR (public Sigstore, no key to hold, like the runtime's npm
provenance; check with `gh attestation verify`); immutable releases from the first public release, which lock tag and assets and add a
release attestation (repository-wide, so Swift's too); and signing apart from building, so a compromised plugin could alter an APK but
not take the key.

## 9. Order of work

| # | Step | Who |
| --- | --- | --- |
| 1 | Publish the runtime prerelease of [section 2](#2-the-runtime-aar) from the approved AAR | Owner's word; an agent may run it |
| 2 | Merge this branch's workflows; `android.yml` runs on the next push | Review |
| 3 | Teams, tag ruleset, both environments, `dev/android` ruleset ([section 5](#5-signing)) | Organisation owner |
| 4 | The GitHub App on `commons`; its client ID and key in `android-signing` | Organisation owner |
| 5 | Move the key into `android-signing`; two offline copies; delete it from the Mac | Owner, no agent running |
| 6 | The long-lived private branch in `commons` with `android-private.yml` | Review in `commons` |
| 7 | First release: a signer tags `commons`, then this repository; the draft is checked and installed; a releaser publishes | Signer, releaser |

**Stays on the Mac:** arm64 AVD runs, screenshots, the kit, the runtime's parity checks, builds of a runtime branch.

## Considered and rejected

- A Gradle download task: custom code beside a pin the build already checks.
- A required reviewer on `android-signing`: a signer's tag push is the authorisation, and a click per signature adds nothing a
  signer could not also click.
- Signing in `commons`: on GitHub Free a private repository has no environments; GitHub Team would cost a seat for every
  organisation member.
- Building the private APK in this repository's runners: public logs could carry its source's paths and warnings.
- The AAR in git: 15.7 MB in public history at each bump.
- `v0.4.0-rc.1` as the first asset: it needs `main`, and drags npm and `Package.swift` along.
- Whole-graph dependency verification, SBOMs, Dependabot on the pins: churn for a small team.
- `sha_pinning_required`: it would fail `dev/swift`'s `@v7` tags.
- A second key for internal builds: testers would uninstall at the first public build.
- `signingConfigs` fed from secrets: the key would sit in a Gradle process with third-party plugins.
- Removing the password prompt on the Mac (a keychain item set to always allow, a password file): any process of that user,
  agents included, would hold a working signing key.
- `applicationIdSuffix` on debug: scripts name the package, and each install would hold its own model.
- `versionCode` from a semver formula: a counter cannot run out of room.
- Firebase App Distribution, Play internal testing, Pgyer, fir.im, object storage in China: too much for two testers.
- R8 off to avoid JNA's rules: they are three lines.

## Not verified

- **`android-release.yml` has not run.** `android.yml` has: its first full run on `ubuntu-24.04` (2026-11-17, a cold
  cache) took about 10 minutes, with every build type, the unit tests, lint, the binary check on Linux and the 16 KB check
  passing. Both workflows pass `actionlint` with `shellcheck`. The signing helper's checks were run on the Mac with a
  throwaway key against 0.2.2's unsigned APK: it signs, refuses another certificate, and tells a changed zip from the
  unsigned one.
- **`gh release download` and `gh release view` on a draft** from `publish`, and the App token's access to `commons`' artifacts.
- **Reach from the teammate's network:** GreatFire's public probes only, and few for the assets host.
- **HyperOS's dialogs** for a signed APK installed by hand.
- **Developer verification:** when sideloads are enforced, and whether HyperOS China builds are "certified".

## Sources

All read on 2026-10-31.

- GitHub: [runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
  [Ubuntu 24.04 image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md),
  [how workflows are found](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows),
  [events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
  [environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments),
  [caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching),
  [secure use](https://docs.github.com/en/actions/reference/security/secure-use),
  [artifacts](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts),
  [Packages and Maven](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry),
  [releases API](https://docs.github.com/en/rest/releases/releases),
  [immutable releases](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/immutable-releases),
  [attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).
- Gradle: [setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
  [its distribution terms](https://github.com/gradle/actions/blob/main/DISTRIBUTION.md).
- Android SDK repository: [packages](https://dl.google.com/android/repository/repository2-3.xml),
  [Google APIs system images](https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml). `build.prop` read from
  `x86_64-33_r17`, `34_r14`, `35_r09`, `36_r07`, `37.0_r08`.
- Emulator: [`main-emulator.cpp`](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-main-dev/android/emulator/main-emulator.cpp)
  on `emu-main-dev` (`cf0f40a`, 2026-10-27), [acceleration](https://developer.android.com/studio/run/emulator-acceleration),
  [ARM translation (2020)](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html).
- Android: [16 KB pages](https://developer.android.com/guide/practices/page-sizes),
  [app optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
  [app signing](https://developer.android.com/studio/publish/app-signing),
  [apksigner](https://developer.android.com/tools/apksigner),
  [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756),
  [developer verification](https://developer.android.com/developer-verification) and its
  [FAQ](https://developer.android.com/developer-verification/guides/faq).
- Firebase Test Lab: [quotas and deprecation](https://firebase.google.com/docs/test-lab/usage-quotas-pricing),
  [migration FAQ](https://firebase.google.com/docs/test-lab/migrate-faq).
- JNA: [FAQ](https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md); the 5.19.1 AAR from Maven Central.
- GreatFire: [github.com](https://en.greatfire.org/https/github.com),
  [release-assets](https://en.greatfire.org/https/release-assets.githubusercontent.com),
  [huggingface.co](https://en.greatfire.org/https/huggingface.co).
