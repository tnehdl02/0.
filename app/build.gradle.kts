
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.binancephonescanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.binancephonescanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Required by MainActivity.kt (ViewPager and PagerAdapter)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.viewpager:viewpager:1.0.0")
}
