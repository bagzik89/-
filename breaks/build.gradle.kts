plugins { id("com.android.application") }

// Версия приложения «Перерывы». Меняйте при каждой новой сборке оболочки.
val appVersionName = "1.0"
val appVersionCode = 1

android {
    namespace = "ru.breaks.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.breaks.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "dash2026key"
            keyAlias = "dashboard"
            keyPassword = "dash2026key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core:1.13.1")
}
