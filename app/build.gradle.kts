plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kakario.comicviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.comicviewer"
        minSdk = 26
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 업데이트되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // 서명 키는 저장소에 두지 않고 GitHub Secrets에서 받는다 (빌드할 때 임시 파일로 풀어서 사용)
        // 같은 키를 계속 써야 업데이트가 덮어써진다
        getByName("debug") {
            val ks = System.getenv("CV_KEYSTORE_PATH")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("CV_KEYSTORE_PASSWORD")
                keyAlias = "androiddebugkey"
                keyPassword = System.getenv("CV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // 안 쓰는 코드·아이콘 정리 (용량 줄이고 조금 더 빠르게)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0") // 개인모드 지문
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // 테두리(외곽선) 아이콘 전체 — 안 쓰는 아이콘은 빌드 때 R8이 빼서 앱 용량은 거의 안 늘어남
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // FTP 접속 라이브러리 (Apache Commons Net)
    implementation("commons-net:commons-net:3.11.1")
}
