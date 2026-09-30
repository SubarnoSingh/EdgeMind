plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val rustProject = rootProject.file("rust/edgememo_qdrant")

val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    commandLine("cargo", "build", "--release", "--manifest-path", rustProject.resolve("Cargo.toml").absolutePath)
    inputs.dir(rustProject.resolve("src"))
    inputs.file(rustProject.resolve("Cargo.toml"))
    val lock = rustProject.resolve("Cargo.lock")
    if (lock.exists()) inputs.file(lock)
}

val copyHostNativeLibrary = tasks.register<Copy>("copyHostNativeLibrary") {
    dependsOn(cargoBuildHost)
    from(rustProject.resolve("target/release/" + System.mapLibraryName("edgememo_qdrant")))
    into(layout.buildDirectory.dir("generated/native-libs/host"))
}

tasks.withType<Test>().configureEach {
    if (name.endsWith("UnitTest")) {
        dependsOn(copyHostNativeLibrary)
    }
}

val androidSdkDir = System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME")
    ?: rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("=")
    ?: throw GradleException("Android SDK location not found")

val ndkVersion = "27.2.12479018"
val ndkDir = File(File(androidSdkDir.removeSuffix("/"), "ndk"), ndkVersion)
val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
val ndkHostTag = when {
    isWindowsHost -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBinDir = File(ndkDir, "toolchains/llvm/prebuilt/$ndkHostTag/bin")
// NDK ships clang as .cmd wrappers and tools as .exe on Windows hosts.
val ndkScriptExt = if (isWindowsHost) ".cmd" else ""
val ndkExeExt = if (isWindowsHost) ".exe" else ""
val rustupCargo = File(System.getProperty("user.home"), ".cargo/bin/cargo$ndkExeExt")

data class AbiSpec(val abi: String, val triple: String, val clang: String)

val androidAbis = listOf(
    AbiSpec("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android24-clang"),
    AbiSpec("x86_64", "x86_64-linux-android", "x86_64-linux-android24-clang"),
)

val cargoAndroidBuildTasks = androidAbis.associate { spec ->
    val buildTask = tasks.register<Exec>("cargoBuildAndroid" + spec.abi.replace("-", "_")) {
        val cargoPath = if (rustupCargo.exists()) rustupCargo.absolutePath else "cargo"
        val targetEnv = spec.triple.replace('-', '_').uppercase()
        commandLine(
            cargoPath,
            "build",
            "--release",
            "--target",
            spec.triple,
            "--manifest-path",
            rustProject.resolve("Cargo.toml").absolutePath,
        )
        environment(
            "PATH" to File(System.getProperty("user.home"), ".cargo/bin").absolutePath + File.pathSeparator + (System.getenv("PATH") ?: ""),
            "CARGO_TARGET_${targetEnv}_LINKER" to File(ndkBinDir, spec.clang + ndkScriptExt).absolutePath,
            "CC_${spec.triple.replace('-', '_')}" to File(ndkBinDir, spec.clang + ndkScriptExt).absolutePath,
            "AR_${spec.triple.replace('-', '_')}" to File(ndkBinDir, "llvm-ar$ndkExeExt").absolutePath,
        )
        inputs.dir(rustProject.resolve("src"))
        inputs.file(rustProject.resolve("Cargo.toml"))
        val lock = rustProject.resolve("Cargo.lock")
        if (lock.exists()) inputs.file(lock)
    }
    val copyTask = tasks.register<Copy>("copyAndroidLib_" + spec.abi) {
        dependsOn(buildTask)
        from(rustProject.resolve("target/${spec.triple}/release/libedgememo_qdrant.so"))
        into(layout.buildDirectory.dir("generated/jniLibs/${spec.abi}"))
    }
    spec.abi to copyTask
}

tasks.configureEach {
    if (name.matches(Regex("merge(?:Debug|Release)JniLibFolders|pre(?:Debug|Release)?Build"))) {
        dependsOn(cargoAndroidBuildTasks.values)
    }
}

android {
    namespace = "com.example.EdgeMemo"
    ndkVersion = ndkVersion
    compileSdk {
        version = release(37)
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(layout.buildDirectory.dir("generated/jniLibs").get().asFile.absolutePath)
        }
    }

    defaultConfig {
        applicationId = "com.example.EdgeMemo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // The backend URL is development configuration, NOT a secret: all cloud
        // credentials live only in the backend environment. Blank disables the
        // real cloud remotes (honest Unimplemented fallbacks remain).
        val cloudBackendUrl = (project.findProperty("cloudBackendUrl") as String?)
            ?: System.getenv("CLOUD_BACKEND_URL")
            ?: ""
        buildConfigField("String", "CLOUD_BACKEND_URL", "\"${cloudBackendUrl.replace("\"", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.pdfbox.android)
    implementation(libs.commonmark)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}