plugins {
    id("com.android.application")
}

android {
    namespace = "com.classicfoo.yttranscripts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.classicfoo.yttranscripts"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
