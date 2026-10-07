plugins {
    alias(libs.plugins.android.application) // 안드로이드 앱을 만드는 플러그인
    alias(libs.plugins.ksp)                 // Room이 코드를 자동으로 만들 때 쓰는 도구 (KSP)
}

android {
    namespace = "com.example.androidlab"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.androidlab"
        minSdk = 24      // 이 버전보다 낮은 안드로이드에서는 설치 불가
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 이 프로젝트가 가져다 쓰는 라이브러리 목록
// libs.xxx.yyy 는 libs.versions.toml 의 xxx-yyy 줄과 연결된다.
dependencies {
    // 기본 안드로이드 라이브러리
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout) // 화면이 ConstraintLayout을 쓰면 필요
    implementation(libs.material)

    // Room (DB)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)               // Room 코드를 자동으로 만들어 주는 부분

    // 영어 OCR (사진 → 글자)
    implementation(libs.mlkit.text.latin)

    // 사진 회전 정보(EXIF) 읽기 (BitmapLoader에서 사용)
    implementation(libs.androidx.exifinterface)

    // 화면에서 lifecycleScope(코루틴) 사용
    implementation(libs.androidx.lifecycle.runtime.ktx)
}