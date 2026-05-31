import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.payto.seller"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.payto.seller"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?)?.toString() ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            val keystore = rootProject.file(
                System.getenv("ANDROID_KEYSTORE_PATH") ?: "release.keystore",
            )
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "payto-ci"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "payto"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: "payto-ci"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            manifestPlaceholders["usesCleartext"] = "true"

            val devServer = (project.findProperty("payto.serverUrl") as String?)
                ?: "http://10.0.2.2:8080"
            val host = URI(devServer).host ?: "10.0.2.2"
            val site = devServer.trimEnd('/') + "/seller"

            resValue("string", "launchUrl", site)
            resValue("string", "hostName", host)
            resValue("string", "fallbackStrategy", "webview")
            resValue(
                "string",
                "assetStatements",
                """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"web","site":"${devServer.trimEnd('/')}/"}}]""",
            )
        }
        release {
            val releaseKeystore = signingConfigs.getByName("release").storeFile
            signingConfig = if (releaseKeystore != null && releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            manifestPlaceholders["usesCleartext"] = "false"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            resValue("string", "launchUrl", "https://payto.fly.dev/seller")
            resValue("string", "hostName", "payto.fly.dev")
            resValue("string", "fallbackStrategy", "webview")
            resValue(
                "string",
                "assetStatements",
                """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"web","site":"https://payto.fly.dev"}}]""",
            )
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
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.6.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.luigivampa92:ndefemulation-android:1.0.0")
}
