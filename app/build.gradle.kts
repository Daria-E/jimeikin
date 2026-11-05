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
import java.util.regex.Pattern
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.mikepenz.aboutlibraries.plugin.android")
}

android {
    namespace = "com.wanderwildwood.jimeikin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wanderwildwood.jimeikin"
        minSdk = 28
        targetSdk = 36
        versionCode = 101
        versionName = "2.18.27"

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

// The Licenses page lists what the build actually packs: this plugin walks each variant's runtime
// dependencies and writes res/raw/aboutlibraries.json (one entry per artifact, with its POM's
// licence). What Gradle cannot see (fonts, data sets, native code, desugaring) and licence texts
// of our own choosing are in config/libraries and config/licenses. Offline: the build fetches
// nothing; the full texts come from res/raw and config/licenses. The app reads the file with
// shared-licences' LicencesJson.kt (no AboutLibraries runtime library).
aboutLibraries {
    offlineMode.set(true)
    collect {
        // config/ and config-full/ merged by licenceConfig, below
        configPath.set(layout.buildDirectory.dir("licence-config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.addAll("funding")
    }
    library {
        mergePlatformArtifacts.set(true)
        // The page lists what the release APK carries. These come only through debugImplementation
        // (Compose's preview tooling), so a debug build would list them too.
        exclusionPatterns.addAll(
            listOf(
                "androidx.compose.ui:ui-tooling", "androidx.compose.ui:ui-tooling-data",
                "androidx.compose.ui:ui-test-manifest", "androidx.compose.material:material",
            ).map { Pattern.compile("^" + Regex.escape(it) + "$") },
        )
    }
}

// Two editions, two lists. config/ is what both carry; config-full/ is what only Music Box has
// (its YouTube parts' libraries). The plugin reads one folder for every variant and adds a config
// entry even where the variant lacks that library, so both folders are merged here and Music Box
// K's lists then drop config-full's entries again: not one word of them reaches its APK
// (checkStoreApk), and no library it does not carry is on its page.
val licenceConfig = tasks.register<Sync>("licenceConfig") {
    from(rootProject.file("config"))
    from(rootProject.file("config-full"))
    into(layout.buildDirectory.dir("licence-config"))
}
val fullOnlyLibraries: List<String> = rootProject.file("config-full/libraries").listFiles().orEmpty()
    .filter { it.extension == "json" }
    .map { (groovy.json.JsonSlurper().parse(it) as Map<*, *>)["uniqueId"].toString() }
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach {
    dependsOn(licenceConfig)
    if (name.startsWith("prepareLibraryDefinitionsStore")) {
        val drop = fullOnlyLibraries
        inputs.property("fullOnlyLibraries", drop)
        doLast {
            outputs.files.asFileTree.matching { include("**/aboutlibraries.json") }.forEach { f ->
                @Suppress("UNCHECKED_CAST")
                val root = groovy.json.JsonSlurper().parse(f) as MutableMap<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val libs = (root["libraries"] as List<Map<String, Any?>>).filter { it["uniqueId"] !in drop }
                val used = libs.flatMap { (it["licenses"] as? List<*>).orEmpty() }.toSet()
                @Suppress("UNCHECKED_CAST")
                root["licenses"] = (root["licenses"] as Map<String, Any?>).filterKeys { it in used }
                root["libraries"] = libs
                f.writeText(groovy.json.JsonOutput.toJson(root))
            }
        }
    }
}

// The build fails when a bundled library has no license, or a license with no full text to show
// (neither its own text from config/licenses nor a family text in res/raw). Mudita review, 4 Nov 2026.
val licenceRawTexts: List<String> = file("src/main/res/raw").listFiles().orEmpty().map { it.name.lowercase() }
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach {
    val raw = licenceRawTexts
    inputs.property("licenceRawTexts", raw) // so the check reruns when a text is added or removed
    doLast {
        fun family(n: String): String? {
            val l = n.lowercase()
            return when {
                "classpath" in l -> "cpe"; "lgpl" in l || "lesser general" in l -> "lgpl"
                "gpl" in l || "general public licen" in l -> "gpl"; "unicode" in l -> "unicode"; "apache" in l -> "apache"
                "mpl" in l || "mozilla" in l -> "mpl"; "mit" in l.split(Regex("[^a-z]+")) -> "mit"; "bsd" in l -> "bsd"
                "ofl" in l || "open font" in l -> "ofl"; "cc-by" in l || "cc by" in l || "cc0" in l || "public domain" in l -> "none"
                else -> null
            }
        }
        val bad = mutableListOf<String>()
        outputs.files.asFileTree.matching { include("**/aboutlibraries.json") }.forEach { f ->
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(f) as MutableMap<String, Any?>
            val lics = (root["licenses"] as? Map<String, Map<String, Any?>>).orEmpty()
            // A library's own license file (config/licenses, with its copyright line) stands in for
            // the bare family entry its POM names: the plugin merges config entries in, it never
            // takes one out, so a library would otherwise list "MIT" twice, once with no text.
            var changed = false
            (root["libraries"] as? List<MutableMap<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                fun fam(k: String) = family((lics[k]?.get("spdxId") ?: lics[k]?.get("name") ?: k).toString())
                fun hasText(k: String) = !(lics[k]?.get("content") as? String).isNullOrBlank()
                val own = keys.filter { hasText(it) }.mapNotNull { fam(it) }.toSet()
                val kept = keys.filterNot { !hasText(it) && fam(it) in own }
                if (kept != keys) { lib["licenses"] = kept; changed = true }
            }
            if (changed) f.writeText(groovy.json.JsonOutput.toJson(root))
            (root["libraries"] as? List<Map<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                if (keys.isEmpty()) bad += "${lib["uniqueId"]}: no license"
                keys.forEach { k ->
                    val e = lics[k].orEmpty()
                    if ((e["content"] as? String).isNullOrBlank()) {
                        val fam = family((e["spdxId"] ?: e["name"] ?: k).toString())
                        if (fam == null || (fam != "none" && raw.none { fam in it }))
                            bad += "${lib["uniqueId"]}: no text for ${e["name"] ?: k}"
                    }
                }
            }
        }
        if (bad.isNotEmpty()) throw GradleException("Licenses page: add the text in config/licenses or res/raw: " + bad.distinct().joinToString("; "))
    }
}
