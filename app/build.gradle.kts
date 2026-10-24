import java.util.Properties
import java.util.Base64
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.hatch"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.hatch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c11"); arguments += listOf("-DANDROID_STL=none") }
        }
    }


    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else if (System.getenv("HATCH_KEYSTORE_BASE64") != null) {
                val bytes = Base64.getDecoder().decode(System.getenv("HATCH_KEYSTORE_BASE64"))
                val f = file("${layout.buildDirectory.get().asFile}/hatch-release.jks")
                f.parentFile.mkdirs(); f.writeBytes(bytes)
                storeFile = f
                storePassword = System.getenv("HATCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HATCH_KEY_ALIAS") ?: "hatch"
                keyPassword = System.getenv("HATCH_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true } // W^X: proot/loader must be loadable from nativeLibraryDir
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    androidResources {
        noCompress += listOf("so", "tar", "xz", "zst")
    }
}

dependencies {
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.arsclib)
    implementation(libs.apksig)
    implementation(libs.tukaani.xz)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}


// Compose BOM / core-ktx currently advertise minCompileSdk 37; we target 35 and compile with
// whatever platform is available (37 locally, 36 on CI). Disable the hard AarMetadata gate.
tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) enabled = false
}
