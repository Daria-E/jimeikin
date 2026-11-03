import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.Collections
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wanderwildwood.jimeikin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wanderwildwood.jimeikin"
        minSdk = 28
        targetSdk = 36
        versionCode = 96
        versionName = "2.18.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Two apps from one source. "full" is Music Box, the GitHub edition, with YouTube Music
    // search, streaming and downloads (src/full). "store" is Music Box K, the one offered to
    // Mudita's store: the same app without any of that - its code, its library
    // (NewPipeExtractor) and its words are not in the APK at all; src/store stands in with a
    // part that finds nothing. It is a separate app, not an edition of the same one: its own
    // application id (so the two install side by side, each with its own data, its own
    // download folder and its own Glance authority), its own name and its own version line
    // starting at 1.0.0. Same signing key. Everything that must differ derives from the
    // application id or from the DOWNLOAD_FOLDER build constant below.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("String", "DOWNLOAD_FOLDER", "\"Music Box\"")
        }
        create("store") {
            dimension = "edition"
            applicationIdSuffix = ".k"
            versionCode = 2
            versionName = "1.0.1"
            buildConfigField("String", "DOWNLOAD_FOLDER", "\"Music Box K\"")
        }
    }

    // The real keystore lives in signing/, which is gitignored. No key is kept in this repo:
    // one anyone can read would let anyone build an APK that installs over this one.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("release") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        // Both build types take the real key when it is here, so a downloaded release APK
        // still upgrades a local debug install in place. Without it a debug build uses this
        // machine's own Android debug key.
        debug {
            if (realSigningConfig != null) signingConfig = realSigningConfig
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No fallback: without the real key a release is unsigned and will not install,
            // which fails loudly instead of producing something signed with the wrong key.
            signingConfig = realSigningConfig

            // Two builds of the same commit differed by 120 bytes, and this was all of it:
            // AGP stamps the git revision into META-INF, CI builds from a clone and the
            // build box builds from an rsync with no .git, so one said the commit and the
            // other said NO_SUPPORTED_VCS_FOUND. Off, and a release APK built here hashes
            // the same as the one GitHub publishes - which is the only way the checksum in
            // a forum post can be checked against anything but itself.
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Music Box K holds no YouTube word at all, and AndroidX's PatternsCompat (the web-address pattern
// behind Linkify) lists "youtube" among its top-level domains. Only in K's build, that one entry
// is taken out of the pattern as the class is packaged; a web address ending in .youtube is then
// simply not underlined as a link, which nothing in this app does anyway.
abstract class DropYouTubeTld : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean = classData.className == "androidx.core.util.PatternsCompat"

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            // The pattern's pieces are compile-time constants: fields with a value, and loads of them.
            override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?) =
                super.visitField(access, name, descriptor, signature, if (value is String) value.replace("|youtube|", "|").replace("youtube|", "") else value)

            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitLdcInsn(value: Any?) {
                        super.visitLdcInsn(if (value is String) value.replace("|youtube|", "|").replace("youtube|", "") else value)
                    }
                }
        }
}

// jimeikin-full-2.18.15.apk, jimeikin-k-1.0.0.apk: which app, and which version, by name.
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(
                output.versionName.map { "jimeikin-${if (variant.flavorName == "store") "k" else variant.flavorName}-$it$suffix.apk" },
            )
        }
    }
}

androidComponents {
    onVariants(selector().withFlavor("edition" to "store")) { variant ->
        variant.instrumentation.transformClassesWith(DropYouTubeTld::class.java, InstrumentationScope.ALL) {}
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.navigation:navigation-compose:2.7.4")

    // Mudita Mindful Design Framework
    // 1.0.2, not 1.0.0. The scrollbar's thumb offset is (trackHeight - thumbHeight), handed
    // to coerceIn as its maximum. In 1.0.0 that subtraction has no floor: when the track
    // measures less than the 16px minimum thumb - which it does while the list is still
    // being laid out - the maximum goes negative and coerceIn throws
    // "maximum -16.0 is less than minimum 0.0" from inside draw, on the main thread, and
    // takes the app down. Confirmed by disassembly rather than by reading the source: at the
    // same offset 1.0.0 is `fsub; fstore` and 1.0.2 is `fsub; fconst_0; coerceAtLeast;
    // fstore`. 1.0.2 also guards the denominator against zero.
    implementation("com.mudita:MMD:1.0.2")

    // Room database for songs
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // SAF helper for working with picked folders
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Media3 (ExoPlayer) for local file playback
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

    "fullImplementation"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "com.google.protobuf")
    }

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is a stub under unit tests; the real one, to parse album pages.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // meta tag library
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
}

// Music Box K holds no YouTube, NewPipe or schabi anywhere: not a class, not a string in its dex,
// its resources or its assets, in any letter case. Run after every K release build, and the
// build fails if one is found.
val checkStoreApk = tasks.register("checkStoreApk") {
    doLast {
        val words = listOf("youtube", "newpipe", "schabi")
        val apks = layout.buildDirectory.dir("outputs/apk/store/release").get().asFile
            .listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        check(apks.isNotEmpty()) { "no store release APK to scan" }
        val found = mutableListOf<String>()
        for (apk in apks) {
            val zip = ZipFile(apk)
            try {
                for (entry in Collections.list(zip.entries())) {
                    if (entry.isDirectory) continue
                    val n: String = entry.name
                    for (w in words) if (n.contains(w, ignoreCase = true)) found += "${apk.name}: file name $n"
                    if (n.endsWith(".dex") || n == "resources.arsc" || n == "AndroidManifest.xml" || n.startsWith("assets/") || n.startsWith("res/")) {
                        val bytes: ByteArray = zip.getInputStream(entry).use { it.readBytes() }
                        // dex and utf-8 string pools hold the word as bytes; a UTF-16 pool holds it with a zero between letters.
                        val latin = String(bytes, Charsets.ISO_8859_1).lowercase()
                        val wide = latin.replace("\u0000", "")
                        for (w in words) if (latin.contains(w) || wide.contains(w)) found += "${apk.name}: $n contains \"$w\""
                    }
                }
            } finally {
                zip.close()
            }
        }
        check(found.isEmpty()) { "Music Box K must not contain YouTube words:\n" + found.joinToString("\n") }
        println("checkStoreApk: ${apks.joinToString { it.name }} clean")
    }
}
tasks.matching { it.name == "assembleStoreRelease" }.configureEach { finalizedBy(checkStoreApk) }
