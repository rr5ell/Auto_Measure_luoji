plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.auto_measure_luoji"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.auto_measure_luoji"
        minSdk = 24
        targetSdk = 36
        versionCode = 830
        versionName = "0.8.30"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("laserCompare") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".lasercompare"
            versionNameSuffix = "-laser"
            matchingFallbacks += listOf("debug")
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    androidResources {
        noCompress += "tflite"
    }
    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.exifinterface)
    implementation("org.apache.commons:commons-compress:1.28.0")

    // LiteRT (YOLO 推理) — 替代 org.tensorflow，16KB 页面对齐
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
    implementation("com.google.ai.edge.litert:litert-api:1.4.2")
    // GMS TFLite 作为可选 fallback，排除所有 org.tensorflow 传递依赖
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0") {
        exclude(group = "org.tensorflow")
    }
    implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0") {
        exclude(group = "org.tensorflow")
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
