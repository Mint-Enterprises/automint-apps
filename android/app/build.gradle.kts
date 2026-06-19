import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

fun signingSecret(name: String): String? {
    keystoreProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val secretFile = keystoreProps.getProperty("${name}File")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return rootProject.file(secretFile).readText().trim().takeIf { it.isNotEmpty() }
}

val releaseStoreFile = keystoreProps.getProperty("storeFile")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseStorePassword = signingSecret("storePassword")
val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseKeyPassword = signingSecret("keyPassword")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrEmpty() }

fun gitOutput(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) {
    ""
}

val gitCommit = gitOutput("rev-parse", "--short", "HEAD")
val gitBranch = gitOutput("rev-parse", "--abbrev-ref", "HEAD")
val gitDirty = gitOutput(
    "status", "--porcelain", "--untracked-files=no",
    "--", ":(exclude).claude", ":(exclude).superpowers",
).isNotEmpty()
val buildTime: String = DateTimeFormatter.ISO_INSTANT
    .format(Instant.ofEpochMilli(System.currentTimeMillis()))

android {
    namespace = "online.automint.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "online.automint.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            "\"https://updates.automint.online/android/latest-android.json\"",
        )
        buildConfigField("String", "DOWNLOAD_URL", "\"https://automint.online/download\"")
        buildConfigField(
            "String", "EXPECTED_SIGNER_SHA256",
            "\"b2845dfad39d503b9384e8d33e7cadc9d8270cd6b62cebdb8e63dda59ee870a0\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField("String", "TARGET_URL", "\"https://897dgo89roy8rgtery7t.lol\"")
            buildConfigField("boolean", "IS_DEV", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "TARGET_URL", "\"https://automint.online\"")
            buildConfigField("boolean", "IS_DEV", "false")
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.sentry.android)

    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.lifecycle.process)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
