import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signing is opt-in; ordinary release builds produce an unsigned community bundle.
val releaseSigningEnabled = when (providers.gradleProperty("cinevault.releaseSigning").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("cinevault.releaseSigning must be true or false.")
}

android {
    namespace = "com.dineshdevkota.cinevault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dineshdevkota.cinevault"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("cinevault.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("cinevault.versionName").orNull ?: "1.0"
    }

    if (releaseSigningEnabled) {
        val signingEnvironment = listOf(
            "CINEVAULT_KEYSTORE_PATH",
            "CINEVAULT_STORE_PASSWORD",
            "CINEVAULT_KEY_ALIAS",
            "CINEVAULT_KEY_PASSWORD",
        ).associateWith { providers.environmentVariable(it).orNull }
        val missing = signingEnvironment.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing requires environment variables: ${missing.joinToString()}")
        }
        val signingValues = signingEnvironment.mapValues { it.value!! }
        val keystore = file(signingValues.getValue("CINEVAULT_KEYSTORE_PATH"))
        if (!keystore.isFile || !keystore.canRead()) {
            throw GradleException("CINEVAULT_KEYSTORE_PATH must point to a readable keystore file.")
        }
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = signingValues.getValue("CINEVAULT_STORE_PASSWORD")
            keyAlias = signingValues.getValue("CINEVAULT_KEY_ALIAS")
            keyPassword = signingValues.getValue("CINEVAULT_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // smbj pulls in BouncyCastle/Kerberos classes that R8 cannot shrink safely.
            isMinifyEnabled = false
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }

    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.smbj)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
