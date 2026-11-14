plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "ai.moduui.jari"
    compileSdk = 37

    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.moduui.jari"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"2.3.2\"")
        buildConfigField("String", "NAVI_SDK_VERSION", "\"3.0.5\"")
        buildConfigField("boolean", "PLEOS_TTS", "false")
        // 보호자 휴대폰 앱과 잇는 중계 서버. 출시 빌드는 팀 서버가 생기면 그 주소를 넣음 (지금은 끔).
        buildConfigField("String", "PHONE_RELAY_URL", "\"\"")
    }

    buildTypes {
        debug {
            // 시연: PC 의 tools/sim/phone-relay.py. 에뮬레이터에서는 adb reverse tcp:8090 tcp:8090 으로 PC 에 닿음.
            buildConfigField("String", "PHONE_RELAY_URL", "\"http://127.0.0.1:8090\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Pleos Connect SDKs
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // Gleo LLM: 쓸 수 있는지 확인하는 디버그 시험(LlmProbe)에만 씀. 출시 빌드에는 넣지 않음.
    debugImplementation("ai.pleos.playground:LLM:2.1.3.2")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // 휴대폰 연결 QR 만들기
    implementation("com.google.zxing:core:3.5.3")

    // 앱 안 지도 (OpenStreetMap 타일). 에뮬레이터 내비는 빈 지도 스타일(connect_empty)이라 앱이 직접 그림
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // JSON & Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
