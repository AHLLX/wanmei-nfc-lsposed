plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mipay.wanmei.lsp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mipay.wanmei.lsp"
        minSdk = 30
        targetSdk = 34
        versionCode = 200
        versionName = "v0.2.0"
    }

    signingConfigs {
        create("mipay") {
            storeFile = file("keystore/mipay.jks")
            storePassword = "mipay1234"
            keyAlias = "mipaygpay"
            keyPassword = "mipay1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("mipay")
        }
        release {
            signingConfig = signingConfigs.getByName("mipay")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.caverock:androidsvg-aar:1.4")
    compileOnly(files("libs/xposed-api.jar"))
}
