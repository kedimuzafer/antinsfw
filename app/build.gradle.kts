plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.antinsfw.antinsfw"
    compileSdk = 35
    
    signingConfigs {
        create("release") {
            storeFile = file("release-key.keystore")
            storePassword = "123456"
            keyAlias = "antinsfw"
            keyPassword = "123456"
        }
    }

    defaultConfig {
        applicationId = "com.antinsfw.antinsfw"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ndk bloğunu tamamen kaldırıyoruz
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true // Önceki hatayı çözmek için bu şekilde güncellendi
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64") // Mimari seçimi burada yönetiliyor
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}