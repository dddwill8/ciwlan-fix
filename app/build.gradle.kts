plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ciwlanfix.lsposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ciwlanfix.lsposed"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
