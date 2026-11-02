plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

apply(from = rootProject.file("gradle/release-signing.gradle.kts"))

android {
    namespace = "io.github.grassified.wristprompt"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.grassified.wristprompt"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "io.github.grassified.wristprompt.images.ImageImportInstrumentation"
    }

    buildFeatures {
        compose = true
    }

    // Shared validation above requires a complete environment; never use debug signing.
    providers.environmentVariable("WRISTPROMPT_RELEASE_STORE_FILE").orNull?.let { path ->
        val production = signingConfigs.create("production") {
            storeFile = file(path)
            storePassword = providers.environmentVariable("WRISTPROMPT_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("WRISTPROMPT_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("WRISTPROMPT_RELEASE_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = production
    }

    // One protocol implementation, compiled into both apps without another Gradle module.
    sourceSets {
        getByName("debug").kotlin.directories.add(rootProject.file("shared/src/debug/kotlin").path)
        getByName("main").kotlin.directories.add(rootProject.file("shared/src/main/kotlin").path)
        getByName("test").kotlin.directories.add(rootProject.file("shared/src/test/kotlin").path)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.exifinterface)
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

extra["wristpromptVersionName"] = android.defaultConfig.versionName!!
apply(from = rootProject.file("gradle/distribution-notices.gradle.kts"))

val distributionAssets = extra["wristpromptDistributionAssets"] as org.gradle.api.file.DirectoryProperty
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(tasks.named("generateDistributionNotices")) { distributionAssets }
}
