plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zizephus.solace.harness"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].jniLibs.directories +=
        providers.gradleProperty("solace.nativeOut")
            .orElse(rootProject.file("../toolchains/out/jniLibs").path)
            .get()
    sourceSets["main"].assets.directories +=
        providers.gradleProperty("solace.engineOut")
            .orElse(rootProject.file("../toolchains/out/engine").path)
            .get()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
