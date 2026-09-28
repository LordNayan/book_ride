import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val rideLocalProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
val openAiPlaceKey = rideLocalProperties.getProperty("ride.openai.apiKey", "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r")

// This personal Rapido build uses Android's on-device speech service.
// The inherited Sarvam classes remain for attribution, but no Sarvam key is bundled.

android {
    namespace = "com.screensaathi"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // AGP defaults to 36.0.0, which is not installed on this machine.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.screensaathi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SARVAM_API_KEY", "\"\"")
        // local.properties is gitignored. The key is still extractable from
        // the installed APK, so this is suitable only for the user's private build.
        buildConfigField("String", "OPENAI_PLACE_API_KEY", "\"$openAiPlaceKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The classes under test call android.util.Log. Without this every
            // Log line throws "not mocked" and the test fails for no reason.
            isReturnDefaultValues = true

            all {
                // The eval suite prints its scorecard and each failing case to
                // stdout. Without this Gradle swallows it and a red build says
                // nothing about WHICH case regressed.
                it.testLogging {
                    showStandardStreams = true
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub that throws. This is the real thing, so
    // the DSL and planner parsers can be tested as they actually behave.
    testImplementation(libs.json)
}
