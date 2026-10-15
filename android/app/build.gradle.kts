plugins { id("com.android.application") }
android {
    namespace = "com.r3dzf.nerqo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.r3dzf.nerqo"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    signingConfigs {
        create("stableRelease") {
            val keystore = System.getenv("NERQO_SIGNING_FILE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("NERQO_STORE_PASSWORD")
                keyAlias = System.getenv("NERQO_KEY_ALIAS")
                keyPassword = System.getenv("NERQO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("stableRelease")
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
