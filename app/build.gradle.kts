import java.io.IOException
import java.util.Properties
import java.util.zip.ZipFile
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The task types below guard the runtime this build links (README.md, Build requirements). They touch nothing of this
// script but their own properties, so Gradle can create them and keep them in a reused configuration.

/**
 * Compares the linked AAR with the pin when the build runs: also with a reused configuration, and when the file at the
 * same path was replaced. It has no outputs, so it runs in every build that reaches `preBuild`.
 */
abstract class VerifyRuntimeAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val actual = sha256(aar.get().asFile)
        val expected = expectedSha256.get()
        if (actual != expected) {
            throw GradleException(
                "Runtime AAR changed or mismatched: ${aar.get().asFile}; expected $expected; actual $actual",
            )
        }
        println("PASS runtime AAR SHA-256 $actual")
    }

    companion object {
        fun sha256(aar: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            aar.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Runs scripts/check-no-kleidiai.py on the runtime's native library taken out of an archive. The verdict is the
 * checker's exit code: 0 passes, anything else fails the build. No outputs, so it runs every time.
 */
abstract class RuntimeLibraryCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checker: RegularFileProperty

    /** `toolchains/llvm/prebuilt` of the pinned NDK. Its host directory is whichever one is there with the tools. */
    @get:Input
    abstract val ndkLlvmPrebuilt: Property<String>

    /** Where the libraries are extracted to, under the build directory. Emptied at each run. */
    @get:Internal
    abstract val workDirectory: DirectoryProperty

    /** Empties the work directory, so that no library an earlier run extracted can be the one checked. */
    protected fun emptyWorkDirectory(): File {
        val directory = workDirectory.get().asFile
        if (directory.exists() && !directory.deleteRecursively()) {
            throw GradleException("Runtime library check: could not empty $directory")
        }
        if (!directory.mkdirs()) throw GradleException("Runtime library check: could not create $directory")
        return directory
    }

    /**
     * Extracts [entry] from [archive] to [target] and runs the checker on it. Returns false, having checked nothing,
     * when the archive has no such entry; a failed extraction, missing tools and a checker exit other than 0 throw.
     */
    protected fun checkEntry(archive: File, entry: String, target: File): Boolean {
        try {
            ZipFile(archive).use { zip ->
                val zipEntry = zip.getEntry(entry) ?: return false
                zip.getInputStream(zipEntry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() != zipEntry.size) {
                    throw IOException("${target.length()} of ${zipEntry.size} bytes extracted")
                }
            }
        } catch (e: IOException) {
            throw GradleException("Runtime library check: could not extract $entry from $archive: ${e.message}", e)
        }

        val command = listOf("python3", checker.get().asFile.path, llvmBin().path, target.path)
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw GradleException(
                "Runtime library check: python3 could not be started (${e.message}). " +
                    "The check needs python3 on the PATH.",
                e,
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        println(output.trimEnd())
        if (exit != 0) {
            throw GradleException(
                "Runtime library check failed for $entry in $archive: scripts/check-no-kleidiai.py exited with $exit " +
                    "(1: forbidden KleidiAI or SME code; 2: invalid input or a tool error). Its output is above.",
            )
        }
        return true
    }

    private fun llvmBin(): File {
        val prebuilt = File(ndkLlvmPrebuilt.get())
        val hosts = prebuilt.listFiles()?.sortedBy { it.name } ?: emptyList()
        return hosts.map { File(it, "bin") }
            .firstOrNull { bin -> File(bin, "llvm-objdump").isFile && File(bin, "llvm-readelf").isFile }
            ?: throw GradleException(
                "Runtime library check: no llvm-objdump and llvm-readelf in a host directory of $prebuilt. " +
                    "The check needs the LLVM tools of the NDK version pinned in gradle/libs.versions.toml; " +
                    "install that NDK.",
            )
    }
}

/** The check on the library inside the linked AAR, before anything is built from it. */
abstract class CheckRuntimeAarLibrary : RuntimeLibraryCheck() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    @TaskAction
    fun checkAar() {
        val archive = aar.get().asFile
        val entry = "jni/arm64-v8a/libensomi_runtime_ffi.so"
        if (!checkEntry(archive, entry, File(emptyWorkDirectory(), "libensomi_runtime_ffi.so"))) {
            throw GradleException("Runtime library check: $archive has no $entry")
        }
    }
}

/**
 * The check on the library inside each APK an assembly left, so that what is packaged cannot differ unnoticed from what
 * was checked in the AAR. It runs as the assembly's finalizer and reads the assembly's output directory.
 */
abstract class CheckApkRuntimeLibrary : RuntimeLibraryCheck() {
    /** Not an input: the directory is another task's output, read after that task. */
    @get:Internal
    abstract val apkDirectory: DirectoryProperty

    /** True for the app's APKs, which must carry the library. False for the instrumentation APK, which links none. */
    @get:Input
    abstract val libraryRequired: Property<Boolean>

    @TaskAction
    fun checkApks() {
        val directory = apkDirectory.get().asFile
        val apks = (directory.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.endsWith(".apk") }
            .sortedBy { it.name }
        if (apks.isEmpty()) {
            throw GradleException(
                "Runtime library check: the assembly left no APK in $directory, so nothing was checked",
            )
        }
        val work = emptyWorkDirectory()
        val entry = "lib/arm64-v8a/libensomi_runtime_ffi.so"
        for (apk in apks) {
            val target = File(File(work, apk.name), "libensomi_runtime_ffi.so")
            if (!target.parentFile.mkdirs()) {
                throw GradleException("Runtime library check: could not create ${target.parentFile}")
            }
            if (checkEntry(apk, entry, target)) continue
            if (libraryRequired.get()) throw GradleException("Runtime library check: $apk has no $entry")
            println("Runtime library check: $apk carries no $entry, as an instrumentation APK should; nothing to check")
        }
    }
}

// Machine-specific settings, which git ignores (see README.md).
val localProperties = Properties().also { properties ->
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
}

// The runtime AAR this build links, from `ensomi.runtime.aar` in local.properties (see README.md).
val runtimeAar: File = run {
    val path = localProperties.getProperty("ensomi.runtime.aar")?.trim()
    if (path.isNullOrEmpty()) {
        throw GradleException(
            "Set ensomi.runtime.aar in local.properties to the absolute path of ensomi-runtime.aar " +
                "(built by ensomi-runtime's scripts/build-android.sh). See README.md.",
        )
    }
    val aar = rootProject.file(path)
    if (!aar.isFile) {
        throw GradleException("ensomi.runtime.aar in local.properties points to $path, which is not a file.")
    }
    aar
}

// Fingerprint the exact local artifact without loading any native code in Diagnostics.
val runtimeIdentity = VerifyRuntimeAar.sha256(runtimeAar)

// The pin. local.properties selects a file, not an exemption: every build type stops here unless the selected AAR is
// the approved one. There is no bypass, and the expected value never comes from the linked file.
val expectedRuntimeIdentity = libs.versions.runtimeAarSha256.get()
if (runtimeIdentity != expectedRuntimeIdentity) {
    throw GradleException(
        "Runtime AAR SHA-256 mismatch: $runtimeAar\n" +
            "  expected $expectedRuntimeIdentity (runtimeAarSha256 in gradle/libs.versions.toml)\n" +
            "  actual   $runtimeIdentity\n" +
            "Point ensomi.runtime.aar in local.properties at the approved AAR. A different runtime means changing " +
            "the pin on purpose, in one commit with the documents, after scripts/check-no-kleidiai.py passes on " +
            "its library (README.md, Build requirements).",
    )
}

android {
    namespace = "io.ensomi.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    // Only used to strip the runtime's native libraries; the app has no native code of its own.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.ensomi.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
        buildConfigField("String", "RUNTIME_AAR_SHA256", "\"$runtimeIdentity\"")
        ndk { abiFilters += "arm64-v8a" }
        // The phone checks (app/src/androidTest, docs/phone-checks.md), run with `am instrument` by
        // scripts/phone-checks.sh.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            // The owner signs the APK separately. Keep rules live in src/release/keepRules.
            optimization { enable = true }
        }
        // The checks build: not debuggable, as a release is not, so the phone checks measure frame pacing without ART's
        // debuggable mode. Signed with the debug key and without R8, so it installs over the debug build (keeping its
        // data, the model with it) and the debug build's instrumented checks can drive it. It keeps the scenarios and
        // frame logs (src/checks/res/values/bools.xml). Never distributed (docs/phone-checks.md).
        create("checks") {
            initWith(getByName("debug"))
            isDebuggable = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Recognition is part of every build. Release keep rules remain in src/release/keepRules.
    sourceSets {
        listOf("debug", "checks", "release").forEach { buildType ->
            getByName(buildType) {
                kotlin.srcDir("src/recognition/kotlin")
                res.srcDir("src/recognition/res")
                manifest.srcFile("src/recognition/AndroidManifest.xml")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

// The binary check's tools: python3 from the PATH, and the LLVM tools of the pinned NDK, under the SDK that
// local.properties names (ANDROID_HOME when it names none, as for the Android Gradle Plugin).
val checkerScript: File = rootProject.file("scripts/check-no-kleidiai.py")
val ndkLlvmPrebuiltPath: String = run {
    val sdk = localProperties.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Set sdk.dir in local.properties to the Android SDK's absolute path. See README.md.")
    File(sdk, "ndk/${libs.versions.ndk.get()}/toolchains/llvm/prebuilt").path
}

// Before anything is built, for every build type, the unit tests and lint: the pin again, then the binary check on the
// library inside the AAR.
val verifyRuntimeAar = tasks.register<VerifyRuntimeAar>("verifyRuntimeAar") {
    aar.set(runtimeAar)
    expectedSha256.set(expectedRuntimeIdentity)
}
val checkRuntimeAarLibrary = tasks.register<CheckRuntimeAarLibrary>("checkRuntimeAarLibrary") {
    dependsOn(verifyRuntimeAar)
    aar.set(runtimeAar)
    checker.set(checkerScript)
    ndkLlvmPrebuilt.set(ndkLlvmPrebuiltPath)
    workDirectory.set(layout.buildDirectory.dir("runtime-library-check/aar"))
}
tasks.named("preBuild") { dependsOn(verifyRuntimeAar, checkRuntimeAarLibrary) }

// After each assembly, the binary check on the library inside the APKs it left: assembleDebug, assembleChecks and
// assembleRelease, whose APKs must carry the library, and assembleDebugAndroidTest, whose APK links none. The tasks are
// found by name and the APKs by the directory the Android Gradle Plugin leaves them in (build/outputs/apk/...); a
// directory without an APK fails the check. A build type without instrumented tests has no such assembly, and its
// second check task never runs.
android.buildTypes.names.forEach { buildType ->
    val variant = buildType.replaceFirstChar { it.uppercase() }
    listOf(
        Triple("assemble$variant", buildType, true),
        Triple("assemble${variant}AndroidTest", "androidTest/$buildType", false),
    ).forEach { (assembly, apkSubdirectory, required) ->
        val checkName = "check" + assembly.removePrefix("assemble") + "ApkRuntimeLibrary"
        val apkCheck = tasks.register<CheckApkRuntimeLibrary>(checkName) {
            apkDirectory.set(layout.buildDirectory.dir("outputs/apk/$apkSubdirectory"))
            libraryRequired.set(required)
            checker.set(checkerScript)
            ndkLlvmPrebuilt.set(ndkLlvmPrebuiltPath)
            workDirectory.set(layout.buildDirectory.dir("runtime-library-check/$checkName"))
        }
        tasks.matching { it.name == assembly }.configureEach { finalizedBy(apkCheck) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)

    // The runtime. A local AAR carries no dependency metadata, so its dependencies are declared here.
    // When a runtime release carries the AAR, a pinned catalog entry replaces files(runtimeAar).
    implementation(files(runtimeAar))
    implementation(libs.jna) { artifact { type = "aar"; extension = "aar" } }
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
