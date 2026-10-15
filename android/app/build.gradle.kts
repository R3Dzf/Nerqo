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
    // Compile unsigned release smoke builds without the private key.
    // Actual release signing requires all four matching environment variables.
    val signingFile = System.getenv("NERQO_SIGNING_FILE")
    val signingPassword = System.getenv("NERQO_STORE_PASSWORD")
    val signingAlias = System.getenv("NERQO_KEY_ALIAS")
    val signingKeyPassword = System.getenv("NERQO_KEY_PASSWORD")
    val signingValues = listOf(signingFile, signingPassword, signingAlias, signingKeyPassword)
    require(signingValues.all { it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) {
        "Incomplete stable Nerqo signing configuration; refusing to build."
    }
    if (!signingFile.isNullOrBlank()) {
        signingConfigs {
            create("stableRelease") {
                storeFile = file(signingFile)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!signingFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("stableRelease")
            }
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
