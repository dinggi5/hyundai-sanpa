import java.util.Properties

plugins {
    id("com.android.application")
}

// 서버 주소와 짝 코드는 저장소에 두지 않는다. local.properties(gitignore)에 적는다:
//   sanpa.server=https://…   sanpa.pair=<영소문자·숫자 6~32자>
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) } // 한글 경로가 들어 있다 — 바이트로 읽으면 깨진다
}

android {
    namespace = "studio.scopic.sanpa"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.scopic.sanpa"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
        buildConfigField("String", "SERVER", "\"${local.getProperty("sanpa.server", "https://example.invalid")}\"")
        // 짝 코드는 차가 처음 켤 때 만든다. 디버그 빌드만 local.properties 의 sanpa.pair 로 고정할 수 있다.
        buildConfigField("String", "PAIR", "\"\"")
    }
    useLibrary("android.car") // 주행 제한(CarUxRestrictionsManager)

    // release 서명 열쇠도 저장소 밖: sanpa.store(파일 경로) · sanpa.storePass · sanpa.keyAlias. 없으면 서명 없이 나온다.
    val store = local.getProperty("sanpa.store")
    signingConfigs {
        if (store != null) create("release") {
            storeFile = file(store)
            storePassword = local.getProperty("sanpa.storePass")
            keyAlias = local.getProperty("sanpa.keyAlias", "sanpa")
            keyPassword = local.getProperty("sanpa.storePass")
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "PAIR", "\"${local.getProperty("sanpa.pair", "")}\"")
        }
        release {
            isMinifyEnabled = false
            if (store != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("com.google.zxing:core:3.5.3") // 가족 연결 QR (Apache-2.0)
}
