import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The release key: from keystore.properties on the laptop, or from the Release workflow's environment. Never in git.
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(name: String, env: String): String? = signing.getProperty(name) ?: System.getenv(env)

// One place for the version: versionCode follows from it (1.2.3 -> 10203), so an update is always "higher".
val rflowVersion = "0.1.0"
val rflowCode = rflowVersion.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "io.github.karthiaiengineer.rflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.karthiaiengineer.rflow"
        minSdk = 29
        targetSdk = 35
        versionCode = rflowCode
        versionName = rflowVersion
    }

    signingConfigs {
        create("release") {
            val store = secret("storeFile", "RFLOW_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = secret("storePassword", "RFLOW_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "RFLOW_KEY_ALIAS")
                keyPassword = secret("keyPassword", "RFLOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Unsigned when no key is set up (a pull request's build); the Release workflow always has one.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
