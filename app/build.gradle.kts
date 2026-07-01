import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Read staging creds from local.properties (never commit real keys).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: default)

android {
    namespace = "com.example.demopartnerapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.demopartnerapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Defaults prefilled into the runtime forms. Override in local.properties.
        buildConfigField("String", "EKIN_PWA_HOST", "\"${prop("EKIN_PWA_HOST", "https://staging.ekincare.com")}\"")
        buildConfigField("String", "EKIN_API_BASE", "\"${prop("EKIN_API_BASE", "https://staging.ekincare.com")}\"")
        buildConfigField("String", "EKIN_PARTNER_SLUG", "\"${prop("EKIN_PARTNER_SLUG")}\"")
        buildConfigField("String", "EKIN_ENCODED_KEY", "\"${prop("EKIN_ENCODED_KEY")}\"")
        buildConfigField("String", "EKIN_ENCODED_IV", "\"${prop("EKIN_ENCODED_IV")}\"")
        buildConfigField("String", "EKIN_ENTITY_ID", "\"${prop("EKIN_ENTITY_ID")}\"")
        buildConfigField("String", "EKIN_API_USERNAME", "\"${prop("EKIN_API_USERNAME")}\"")
        buildConfigField("String", "EKIN_API_PASSWORD", "\"${prop("EKIN_API_PASSWORD")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
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
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}