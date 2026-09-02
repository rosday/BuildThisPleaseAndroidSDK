plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.buildthisplease.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.buildthisplease.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        buildConfigField("String", "BTP_STAGING_BASE_URL", "\"${providers.gradleProperty("BTP_STAGING_BASE_URL").orElse("").get()}\"")
        buildConfigField("String", "BTP_STAGING_PROJECT_KEY", "\"${providers.gradleProperty("BTP_STAGING_PROJECT_KEY").orElse("").get()}\"")
        buildConfigField("String", "BTP_PRODUCTION_BASE_URL", "\"${providers.gradleProperty("BTP_PRODUCTION_BASE_URL").orElse("").get()}\"")
        buildConfigField("String", "BTP_PRODUCTION_PROJECT_KEY", "\"${providers.gradleProperty("BTP_PRODUCTION_PROJECT_KEY").orElse("").get()}\"")
    }
}

dependencies {
    implementation(project(":buildthisplease-compose"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
