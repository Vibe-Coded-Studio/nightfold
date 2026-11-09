import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vibecodedstudio.nightfold"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vibecodedstudio.nightfold"
        minSdk = 34          // RuntimeShader (AGSL) 33, special-use foreground service 34
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.2"
    }
    // Release signing: set NIGHTFOLD_SIGNING in ~/.gradle/gradle.properties to a properties file with
    // storeFile, storePassword, keyAlias, keyPassword. Without it the release build is left unsigned.
    val signingProps = (findProperty("NIGHTFOLD_SIGNING") as String?)?.let { file(it) }?.takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (signingProps != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
