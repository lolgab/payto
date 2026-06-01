import java.util.Properties

plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
    localProperties.load(it)
}

extra["paytoServerUrl"] =
    findProperty("payto.serverUrl") as String?
        ?: localProperties.getProperty("payto.serverUrl")
        ?: "http://10.0.2.2:8080"
