plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.r3dzf.nerqo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.r3dzf.nerqo"
        minSdk = 26
        targetSdk = 35
        versionCode = 10007
        versionName = "1.0.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    val signingFile = System.getenv("NERQO_SIGNING_FILE")
    val storePasswordEnv = System.getenv("NERQO_STORE_PASSWORD")
    val aliasEnv = System.getenv("NERQO_KEY_ALIAS")
    val keyPasswordEnv = System.getenv("NERQO_KEY_PASSWORD")
    val credentials = listOf(signingFile, storePasswordEnv, aliasEnv, keyPasswordEnv)
    require(credentials.all { it.isNullOrBlank() } || credentials.all { !it.isNullOrBlank() }) {
        "Incomplete Android release signing configuration"
    }
    if (!signingFile.isNullOrBlank()) {
        signingConfigs {
            create("stableRelease") {
                storeFile = file(signingFile)
                storePassword = storePasswordEnv
                keyAlias = aliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!signingFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("stableRelease")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
