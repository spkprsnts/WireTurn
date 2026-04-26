plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wireturn.app"
    compileSdk = project.property("project.compileSdk").toString().toInt()

    defaultConfig {
        applicationId = "com.wireturn.app"
        minSdk = project.property("project.minSdk").toString().toInt()
        targetSdk = project.property("project.targetSdk").toString().toInt()
        versionCode = 44
        versionName = "4.5.1"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    compileSdkMinor = 1
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
