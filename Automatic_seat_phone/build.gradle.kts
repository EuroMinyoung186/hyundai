plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

// 모두의 자리 · 보호자 휴대폰 앱 (디자인 P-1 · P-2 · P-4 · P-8)
android {
    namespace = "ai.moduui.jari.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.moduui.jari.phone"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        // 차와 잇는 중계 서버. 시연: PC 의 tools/sim/phone-relay.py 를 USB(adb reverse tcp:8090 tcp:8090)로 씀.
        buildConfigField("String", "RELAY_URL", "\"http://127.0.0.1:8090\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    testImplementation(libs.junit)
}
