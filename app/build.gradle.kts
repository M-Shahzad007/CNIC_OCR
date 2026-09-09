plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.cnic_ocr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.cnic_ocr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = file("doc/ocrkeystore.jks")
            storePassword = "Thanks@123"
            keyAlias = "key0"
            keyPassword = "Thanks@123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled =  true
            isShrinkResources  = false
            isDebuggable  = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled  = false
            isShrinkResources =  false
            isDebuggable  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    buildFeatures{
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {

    implementation(project(":cnic_ocr_detection"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation("com.google.mlkit:face-detection:16.1.7")
}