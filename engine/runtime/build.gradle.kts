plugins {
    id("com.android.library")
}
android {
    namespace = "de.mrlongnight.llmreference.engine.runtime"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation(project(":engine:api")) }
