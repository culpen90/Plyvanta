plugins {
    id("com.android.application")
}

val baseVersionName = "1.0.0"
val appVersionCode = 3
val debugPreviewNumber = 3
val debugPreviewVersion = "$baseVersionName-debug.$debugPreviewNumber"

val generatedLegalAssetsDirectory = layout.buildDirectory.dir("generated/legalAssets")
val prepareLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE.md"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses"))
    into(generatedLegalAssetsDirectory.map { it.dir("legal") })
}

android {
    namespace = "app.plyvanta"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.plyvanta"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = baseVersionName
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug.$debugPreviewNumber"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedLegalAssetsDirectory)
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}

val packageDebugPreview by tasks.registering(Sync::class) {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/preview"))
    rename("app-debug.apk", "Plyvanta-$debugPreviewVersion.apk")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.annotation:annotation:1.10.0")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4") {
        // The JSR-223 adapter targets desktop Java; Android uses Rhino directly.
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
