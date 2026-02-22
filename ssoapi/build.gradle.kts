plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.example.ssoapi"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Debug variant — easier to inspect, not minified
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
        singleVariant("debug") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            // Release: implementation("com.example:ssoapi:1.0.0")
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.example"
                artifactId = "ssoapi"
                version = "1.0.0"
            }
            // Debug: implementation("com.example:ssoapi-debug:1.0.0")
            create<MavenPublication>("debug") {
                from(components["debug"])
                groupId = "com.example"
                artifactId = "ssoapi-debug"
                version = "1.0.0"
            }
        }
    }
}
