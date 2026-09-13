import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}
val hasReleaseSigning = keystorePropertiesFile.exists() && keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.lumipos.app"
    compileSdk = AppConfig.compileSdk

    defaultConfig {
        applicationId = "com.lumipos.app"
        minSdk = AppConfig.minSdk
        targetSdk = AppConfig.targetSdk
        versionCode = Versions.getVersionCode()
        versionName = Versions.getVersionName()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = AppConfig.jvmTarget
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(project(":common"))

    // Core
    implementation("androidx.core:core-ktx:${Depedencies.kotlinCoreVersion}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${Depedencies.lifecycleVersion}")
    implementation("androidx.activity:activity-compose:${Depedencies.activityComposeVersion}")

    // Compose (BOM)
    implementation(platform("androidx.compose:compose-bom:${Depedencies.composeBomVersion}"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:${Depedencies.navigationComposeVersion}")

    // Serialization (type-safe navigation)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${Depedencies.lifecycleVersion}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${Depedencies.lifecycleVersion}")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${Depedencies.lifecycleVersion}")

    // Hilt
    implementation("com.google.dagger:hilt-android:${Depedencies.hiltVersion}")
    implementation("androidx.hilt:hilt-navigation-compose:${Depedencies.hiltNavigationComposeVersion}")
    ksp("com.google.dagger:hilt-compiler:${Depedencies.hiltVersion}")

    // Image Loading
    implementation("io.coil-kt:coil-compose:${Depedencies.coilVersion}")

    // Test
    testImplementation("junit:junit:${Depedencies.junitVersion}")
    androidTestImplementation("androidx.test.ext:junit:${Depedencies.junitExtVersion}")
    androidTestImplementation("androidx.test.espresso:espresso-core:${Depedencies.espressoVersion}")
}
