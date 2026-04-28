import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

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
        
        val date = Date()
        val sdf = SimpleDateFormat("yyMMddHH", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val formattedDate = sdf.format(date)
        versionCode = formattedDate.toInt()
        versionName = project.property("project.versionName").toString()
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
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libtun2socks.so"
            keepDebugSymbols += "**/libturnable.so"
            keepDebugSymbols += "**/libvkturn.so"
            keepDebugSymbols += "**/libxray.so"
        }
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    compileSdkMinor = 1
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        val branch = providers.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        }.standardOutput.asText.get().trim()
        
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()

        val isUnstable = branch == "unstable" || System.getenv("GITHUB_REF_NAME") == "unstable"
        val baseVersion = android.defaultConfig.versionName ?: "0.0"
        val vName = if (isUnstable) "$baseVersion-unstable-$gitHash" else baseVersion
        
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"
            // Для unstable убираем суффикс из имени файла, чтобы GitHub перезаписывал ассеты
            val fileSuffix = "" 
            
            // Устанавливаем версию (с хешем для отображения в приложении)
            output.versionName.set(vName)
            // Имя файла (без хеша для стабильной ссылки в релизах)
            val fileNameBase = if (isUnstable) "$baseVersion-unstable" else vName
            output.outputFileName.set("WireTurn_v${fileNameBase}${fileSuffix}_${abi}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
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

tasks.register<Exec>("buildGoBinaries") {
    group = "build"
    description = "Compiles Go binaries for Android"
    workingDir = rootDir

    // Enable incremental builds: only run if Go files or build script changed
    inputs.dir(file("${rootDir}/external")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(file("${rootDir}/build.sh")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(file("${projectDir}/src/main/jniLibs"))

    // Pass NDK path from AGP to script (AGP 8.x / 9.x compatible)
    val androidComponents = project.extensions.findByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()
    val ndkDir = try {
        androidComponents?.sdkComponents?.ndkDirectory?.orNull?.asFile?.absolutePath
    } catch (_: Exception) {
        null
    }

    if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        if (ndkDir != null) {
            // Use WSLENV with /p flag to auto-convert Windows path to WSL path
            environment("NDK_PATH", ndkDir)
            val currentWslEnv = System.getenv("WSLENV") ?: ""
            if (!currentWslEnv.contains("NDK_PATH/p")) {
                environment("WSLENV", if (currentWslEnv.isEmpty()) "NDK_PATH/p" else "$currentWslEnv:NDK_PATH/p")
            }
        }
        // Use login shell to load PATH and wslpath for reliable mapping
        commandLine("wsl", "bash", "-l", "-c",
            $$"cd $(wslpath '$${rootDir.absolutePath}') && ./build.sh"
        )
    } else {
        if (ndkDir != null) environment("NDK_PATH", ndkDir)
        commandLine("bash", "-c", "chmod +x build.sh && ./build.sh")
    }
}

// Заставляем Android собирать бинарники перед компиляцией Java/Kotlin кода
tasks.named("preBuild") {
    dependsOn("buildGoBinaries")
}
