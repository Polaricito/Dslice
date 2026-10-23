import java.util.Properties

plugins {
    id("com.android.application")
}

fun Properties.hasSigningValues(): Boolean = listOf(
    "KEYSTORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
).all { !getProperty(it).isNullOrBlank() }

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
if (signingPropsFile.exists()) signingProps.load(signingPropsFile.inputStream())
val localReleaseSigningConfigured = signingProps.hasSigningValues()

val ciSigningProps = Properties()
val ciSigningPropsFile = rootProject.file("ci/ci-signing.properties")
if (ciSigningPropsFile.exists()) ciSigningProps.load(ciSigningPropsFile.inputStream())
val ciSigningConfigured = ciSigningProps.hasSigningValues()

val releaseSigningProps = when {
    localReleaseSigningConfigured -> signingProps
    ciSigningConfigured -> ciSigningProps
    else -> null
}

android {
    namespace = "df.D10slice"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "df.D10slice"
        minSdk = 32
        targetSdk = 37
        versionCode = 601
        versionName = "6.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        releaseSigningProps?.let { props ->
            create("release") {
                storeFile = if (localReleaseSigningConfigured) {
                    file(props.getProperty("KEYSTORE_FILE"))
                } else {
                    rootProject.file(props.getProperty("KEYSTORE_FILE"))
                }
                storePassword = props.getProperty("KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            releaseSigningProps?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "Dslice_${vn}.apk" })
        }
    }
}

dependencies {
    implementation(libs.material)
}
