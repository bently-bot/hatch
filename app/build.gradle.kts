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
        versionCode = 2
        versionName = "0.2.0"
        // Remote store catalogue (bently-bot/hatch-catalogue). The URL is overridable at runtime (Settings → developer)
        // and via -PcatalogueUrl; the repo is private for now, so until it's public the app keeps its bundled copy.
        buildConfigField("String", "CATALOGUE_URL", "\"${(project.findProperty("catalogueUrl") as String?) ?: "https://github.com/bently-bot/hatch-catalogue/releases/latest/download/catalogue.json.gz"}\"")
        buildConfigField("String", "CATALOGUE_PUBKEY", "\"IQTIjVRzu2yJB2iJwpwm0K58b2+47ubHJ6MBTm7kqhg=\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Roborazzi: always write the screenshots (build/outputs/roborazzi), CI uploads them.
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
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
    // Prebuilt rootfs (.tar.zst) stream decompression: native zstd (AAR: arm64-v8a + x86_64 kept by abiFilters)
    implementation("${libs.zstd.jni.get()}@aar")
    implementation(libs.okhttp)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(libs.androidx.work)
    testImplementation(libs.junit)
    // Compose previews + Roborazzi/Robolectric screenshot tests (phone and unfolded Fold)
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation(libs.zstd.jni)   // host jar (linux natives) for JVM tests
}


// Compose BOM / core-ktx currently advertise minCompileSdk 37; we target 35 and compile with
// whatever platform is available (37 locally, 36 on CI). Disable the hard AarMetadata gate.
tasks.configureEach {
    if (name.contains("AarMetadata", ignoreCase = true)) enabled = false
}
