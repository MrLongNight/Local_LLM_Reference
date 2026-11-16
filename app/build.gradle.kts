plugins {
    id("com.android.application")
}

android {
    namespace = "de.mrlongnight.llmreference"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.mrlongnight.llmreference"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":engine:runtime"))
    implementation(project(":benchmark"))
}
