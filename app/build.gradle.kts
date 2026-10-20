import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Rust core integration (Phase 1) -------------------------------------
// The shared `igris-protocol` crate lives one directory up from this project.
val igrisProtocolDir = rootProject.file("../igris-protocol")
val androidSdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val ndkRoot = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(androidSdkRoot.map { "$it/ndk/29.0.14206865" })

// ABI -> cargo-ndk triple map. arm64-v8a is the phone; the others keep the
// pipeline honest for any device.
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

// `cargo ndk -t <abi>... build --release` inside igris-protocol.
val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "igris"
    description = "Cross-compile the shared Rust core (igris-protocol) for Android"
    workingDir = igrisProtocolDir
    environment("ANDROID_NDK_HOME", ndkRoot.get())
    environment("ANDROID_HOME", androidSdkRoot.get())
    val args = mutableListOf("ndk")
    rustTargets.keys.forEach { abi -> args += listOf("-t", abi) }
    args += listOf("build", "--release")
    commandLine("cargo", *args.toTypedArray())
}

// Copy the built .so files into jniLibs/<abi>.
val copyRustLibs by tasks.registering {
    group = "igris"
    description = "Copy the igris-protocol .so files into jniLibs"
    dependsOn(cargoNdkBuild)
    doLast {
        val outBase = layout.projectDirectory.dir("src/main/jniLibs")
        rustTargets.forEach { (abi, triple) ->
            val so = igrisProtocolDir.resolve("target/$triple/release/libigris_protocol.so")
            if (so.exists()) {
                copy {
                    from(so)
                    into(outBase.dir(abi).asFile)
                }
            } else {
                throw GradleException("Missing native lib for $abi: expected $so")
            }
        }
    }
}

// Generate the Kotlin bindings (library mode) from the arm64 cdylib.
val generateUniffi by tasks.registering(Exec::class) {
    group = "igris"
    description = "Generate Kotlin bindings from the igris-protocol cdylib"
    dependsOn(cargoNdkBuild)
    workingDir = igrisProtocolDir
    val lib = igrisProtocolDir.resolve("target/aarch64-linux-android/release/libigris_protocol.so")
    val outDir = layout.projectDirectory.dir("src/main/kotlin").asFile
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--",
        "generate", lib.absolutePath, "-l", "kotlin", "-o", outDir.absolutePath, "--no-format",
    )
}

tasks.named("preBuild") {
    dependsOn(copyRustLibs, generateUniffi)
}

android {
    namespace = "com.igris.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.igris.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("net.java.dev.jna:jna:5.16.0@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
