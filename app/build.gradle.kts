import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

abstract class CopyPreviewApk : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceApk: RegularFileProperty

    @get:OutputFile
    abstract val previewApk: RegularFileProperty

    @TaskAction
    fun copy() {
        val source = sourceApk.get().asFile.toPath()
        val output = previewApk.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING)
    }
}

abstract class GenerateUpdateMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val apkFile: RegularFileProperty

    @get:OutputFile
    abstract val metadataFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val channel: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val minimumSdk: Property<Int>

    @TaskAction
    fun generate() {
        val apk = apkFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { byte: Byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        val output = metadataFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            {
              "schemaVersion": 1,
              "packageName": "${packageName.get()}",
              "channel": "${channel.get()}",
              "versionCode": ${versionCode.get()},
              "versionName": "${versionName.get()}",
              "minimumSdk": ${minimumSdk.get()},
              "apkName": "${apk.name}",
              "sha256": "$sha256"
            }
            """.trimIndent() + "\n"
        )
    }
}

val baseVersionName = "1.0.0"
val appVersionCode = 4
val debugPreviewNumber = 4
val debugPreviewVersion = "$baseVersionName-debug.$debugPreviewNumber"
val debugPreviewApkName = "Plyvanta-$debugPreviewVersion.apk"
val debugPreviewMetadataName = "Plyvanta-$debugPreviewVersion-update.json"

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

val copyDebugPreviewApk by tasks.registering(CopyPreviewApk::class) {
    dependsOn("assembleDebug")
    sourceApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    previewApk.set(
        layout.buildDirectory.file("outputs/preview/$debugPreviewApkName")
    )
}

val generateDebugPreviewMetadata by tasks.registering(GenerateUpdateMetadata::class) {
    dependsOn(copyDebugPreviewApk)
    apkFile.set(layout.buildDirectory.file("outputs/preview/$debugPreviewApkName"))
    metadataFile.set(
        layout.buildDirectory.file("outputs/preview/$debugPreviewMetadataName")
    )
    packageName.set("app.plyvanta.debug")
    channel.set("preview")
    versionCode.set(appVersionCode)
    versionName.set(debugPreviewVersion)
    minimumSdk.set(26)
}

val packageDebugPreview by tasks.registering {
    dependsOn(generateDebugPreviewMetadata)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.work:work-runtime:2.11.2")

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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
