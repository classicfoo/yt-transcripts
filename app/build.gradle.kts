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
        versionCode = 5
        versionName = "1.0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
