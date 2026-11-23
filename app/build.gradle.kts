plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.superfiles.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.superfiles.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.2"
    }
    signingConfigs {
        // Release signing comes from GitHub Actions secrets (KEYSTORE_FILE etc.).
        // Local builds without them fall back to an unsigned release config.
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.smali:smali:2.5.2")
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:dexlib2:2.5.2")
    // Archive browsing: ZIP/TAR/7Z via Commons Compress, RAR (read-only) via junrar.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.junrar:junrar:7.5.2")
    implementation("org.tukaani:xz:1.10")
}
