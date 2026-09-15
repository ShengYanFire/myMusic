plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mymusic.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mymusic.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8 code shrinking + resource shrinking: drops unused Compose /
            // OkHttp / Media3 code and strips unused resources, roughly
            // halving the APK size. The Gson keep rules in
            // proguard-rules.pro protect the reflection-based model classes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No release keystore is checked in (deliberately): a real
            // release build sets the four RELEASE_* env vars; without them
            // the release build signs with the debug key so it stays
            // installable for personal use.
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                signingConfig = signingConfigs.create("releaseEnv") {
                    storeFile = file(storeFilePath)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // The production code logs with android.util.Log (e.g. the queue
        // context breadcrumb); in a plain JVM unit test that would throw
        // "Method d in android.util.Log not mocked" — return default values so
        // those calls become no-ops instead of failing the test.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.okhttp)
    implementation(libs.gson)

    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests (src/test): run with `gradlew :app:testDebugUnitTest`.
    // NOTE: these two artifacts are NOT in the offline Gradle cache, so the
    // sandbox build verifies the app only; running the tests needs a
    // networked machine (plain `gradlew test` resolves them once).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
