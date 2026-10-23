import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Everything TMPlayer does that is not a screen and not Android's: the TDLib client, the
// repositories and models, settings, resume records, cache policy, the download queue, update
// checks and the streaming byte window. Shared by the Android app and the desktop app.
//
// Two targets, android and jvm, and nothing else. A source set shared only by JVM targets may use
// the JDK, so commonMain is ordinary JVM Kotlin (java.io.File, HttpURLConnection and the rest);
// what genuinely differs per platform comes in through the small interfaces in
// com.tmplayer.platform and the runner interfaces next to the code that needs them.
//
// The Android target uses AGP's own Kotlin Multiplatform library plugin rather than
// com.android.library with androidTarget(): AGP 9 does not let the two be combined, and a library
// with no resources and no build variants is exactly what the plugin is for (one variant,
// consumed by every build type of :app).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Not for any UI: the Compose compiler infers which classes are stable and records it in the
    // bytecode. Without it every model here would look unstable to :app's screens, which would
    // then redraw rows that had not changed.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The UI text catalog, i18n/en.json, turned into the typed `Strings` interface and `object L`
// (com.tmplayer.i18n), so code asks for `L.commonRetry` or `L.browseVideosCount(n)` and a key
// that is not in the catalog fails the build. Each key becomes its dotted path in camel case;
// each ICU argument becomes a parameter: a plural takes a Number, a select a String, a plain
// `{name}` Any. The Kotlin parser in Icu.kt is the real one; this reads only the argument names,
// and CatalogTest checks the two agree.
class IcuArgs(private val src: String) {
    val args = LinkedHashMap<String, String>()
    private var pos = 0
    private var pluralDepth = 0

    init {
        message()
        if (pos < src.length) fail("unmatched }")
    }

    private fun fail(message: String): Nothing = throw GradleException("i18n: $message at $pos in \"$src\"")

    private fun skip() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun word(): String {
        skip()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "_=:")) pos++
        if (start == pos) fail("expected a name")
        return src.substring(start, pos)
    }

    private fun expect(c: Char) {
        skip()
        if (src.getOrNull(pos) != c) fail("expected '$c'")
        pos++
    }

    private fun message() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\'' -> {
                    val next = src.getOrNull(pos + 1)
                    if (next == '{' || next == '}' || (pluralDepth > 0 && next == '#')) {
                        pos = src.indexOf('\'', pos + 1).takeIf { it >= 0 } ?: fail("unterminated quote")
                    }
                    pos++
                    if (next == '\'') pos++
                }
                c == '{' -> argument()
                c == '}' -> return
                else -> pos++
            }
        }
    }

    private fun argument() {
        expect('{')
        val name = word()
        skip()
        if (src.getOrNull(pos) == '}') {
            pos++
            args.putIfAbsent(name, "VALUE")
            return
        }
        expect(',')
        val type = word()
        if (type != "plural" && type != "select") fail("unsupported type '$type'")
        args[name] = type.uppercase()
        expect(',')
        while (true) {
            skip()
            if (pos >= src.length) fail("unterminated argument")
            if (src[pos] == '}') break
            word()
            expect('{')
            if (type == "plural") pluralDepth++
            message()
            if (type == "plural") pluralDepth--
            expect('}')
        }
        expect('}')
    }
}

fun flattenCatalog(prefix: String, node: Map<*, *>, out: MutableMap<String, String>) {
    for ((name, value) in node) {
        val key = if (prefix.isEmpty()) "$name" else "$prefix.$name"
        when (value) {
            is Map<*, *> -> flattenCatalog(key, value, out)
            is String -> out[key] = value
            else -> throw GradleException("i18n: $key is not text")
        }
    }
}

fun accessorName(key: String): String {
    val words = key.split('.', '_').filter { it.isNotEmpty() }
    val name = words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    if (!Regex("[a-z][A-Za-z0-9]*").matches(name)) throw GradleException("i18n: key '$key' makes no Kotlin name")
    return name
}

fun kotlinString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""

val generateStrings = tasks.register("generateStrings") {
    val source = file("src/commonMain/resources/i18n/en.json")
    val out = layout.buildDirectory.dir("generated/i18n")
    inputs.file(source)
    outputs.dir(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(source, "UTF-8") as Map<String, Any?>
        val catalog = sortedMapOf<String, String>().also { flattenCatalog("", root, it) }
        val names = HashMap<String, String>()
        val members = StringBuilder()
        for ((key, text) in catalog) {
            val name = accessorName(key)
            names.put(name, key)?.let { throw GradleException("i18n: '$it' and '$key' both make $name") }
            val args = IcuArgs(text).args
            val doc = text.replace("*/", "*\\/").replace("\n", " ")
            members.append("\n    /** $doc */\n")
            if (args.isEmpty()) {
                members.append("    val $name: String get() = messages.text(${kotlinString(key)})\n")
            } else {
                val params = args.entries.joinToString(", ") { (arg, kind) ->
                    val type = when (kind) { "PLURAL" -> "Number"; "SELECT" -> "String"; else -> "Any" }
                    "$arg: $type"
                }
                val pairs = args.keys.joinToString(", ") { "${kotlinString(it)} to $it" }
                members.append("    fun $name($params): String = messages.format(${kotlinString(key)}, $pairs)\n")
            }
        }
        val dir = out.get().asFile.resolve("com/tmplayer/i18n").apply { mkdirs() }
        dir.resolve("Strings.kt").writeText(
            "package com.tmplayer.i18n\n\n" +
                "// Generated by :core's generateStrings from i18n/en.json. Do not edit.\n\n" +
                "/** Every UI string, typed. [Messages] is one language's; [L] is always the current one. */\n" +
                "interface Strings {\n    val messages: Messages\n$members}\n\n" +
                "/** The current UI language's strings, for code outside Compose. Compose reads LocalStrings. */\n" +
                "object L : Strings {\n    override val messages: Messages get() = Translator.messages\n}\n\n" +
                "/** Every catalog key, sorted, as the build saw it. */\n" +
                "internal val CATALOG_KEYS: List<String> = listOf(\n" +
                catalog.keys.joinToString("") { "    ${kotlinString(it)},\n" } + ")\n",
        )
    }
}

// tdl-coroutines in the browser (CP92). The library is Kotlin over TDLib's JSON interface, and
// all of it but the three files that reach the native library is common code: its sources jar
// (Apache-2.0, the same version the apps use) is fetched, checked by SHA-256 and compiled for
// wasmJs, with core/src/wasmJsMain/kotlin/dev/g000sha256/tdl/TdlWebEngine.kt standing in for the
// native part over tdweb. The apps keep the published artifact.
val tdlVersion = "15.0.0"
val tdlSourcesSha256 = "f526d033e4f12f6debf03edd0ad4a8782eac8c99af55e57aae20c08f41544d5f"

val fetchTdlSources = tasks.register("fetchTdlSources") {
    val jar = layout.buildDirectory.file("tdl-web/tdl-coroutines-$tdlVersion-sources.jar")
    val out = layout.buildDirectory.dir("tdl-web/kotlin")
    inputs.property("version", tdlVersion)
    inputs.property("sha256", tdlSourcesSha256)
    outputs.dir(out)
    doLast {
        val file = jar.get().asFile
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!file.exists() || sha(file.readBytes()) != tdlSourcesSha256) {
            val url = "https://repo1.maven.org/maven2/dev/g000sha256/tdl-coroutines/$tdlVersion/tdl-coroutines-$tdlVersion-sources.jar"
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            if (sha(bytes) != tdlSourcesSha256) throw GradleException("tdl-coroutines $tdlVersion sources do not match their SHA-256")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val skip = setOf("TdlEngine.kt", "TdlNative.kt", "TdlServiceLocator.kt", "JsonClient.kt")
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith("commonMain/") || !entry.name.endsWith(".kt")) continue
                if (entry.name.substringAfterLast('/') in skip) continue
                val target = dir.resolve(entry.name.removePrefix("commonMain/"))
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        // tdweb's TDLib (1.8.64) is older than the schema these sources were generated from
        // (1.8.67): where a type changed in between, the reader takes the older shape as well.
        // A draft was `input_message_text` before it became `content`.
        val reader = dir.resolve("dev/g000sha256/tdl/TdlDeserializer.kt")
        val draft = """content = jsonObject.getObject(key = "content") { data -> deserializeDraftMessageContent(jsonObject = data) },"""
        val text = reader.readText()
        if (draft !in text) throw GradleException("tdl-coroutines: the draft message reader changed; review the tdweb compatibility patch")
        reader.writeText(
            text.replace(
                draft,
                """content = if (jsonObject.containsKey("content")) jsonObject.getObject(key = "content") { data -> deserializeDraftMessageContent(jsonObject = data) } """ +
                    """else DraftMessageContentText(text = jsonObject.getObjectNullable(key = "input_message_text") { input -> input.getObjectNullable(key = "text") { t -> deserializeFormattedText(jsonObject = t) } } """ +
                    """?: FormattedText(text = "", entities = emptyArray()), linkPreviewOptions = null),""",
            ),
        )
    }
}

// The JVM half's files the browser build compiles as well (core/web-sources.txt): Td, the chat
// repository, the settings store and what they need, now that tdl-coroutines is there too. The
// same sources, copied into the wasmJs compilation only, so the apps' build does not change.
val coreWebSources = tasks.register<Sync>("coreWebSources") {
    val list = layout.projectDirectory.file("web-sources.txt")
    inputs.file(list)
    val names = list.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    from(layout.projectDirectory.dir("src/commonMain/kotlin")) { include(names) }
    into(layout.buildDirectory.dir("core-web/kotlin"))
    // The few JVM spellings with a plain Kotlin twin, rewritten in the copy only: the browser has
    // one thread (no IO dispatcher, nothing to synchronise) and locale free case mapping. What
    // else the files name of the JDK has a small stand in under core/src/wasmJsMain/kotlin/java.
    val localeCase = Regex("""\.(lowercase|uppercase)\((java\.util\.)?Locale\.(ROOT|US|ENGLISH)\)""")
    filter { line ->
        val text = line
            .replace("Dispatchers.IO", "Dispatchers.Default")
            .replace(localeCase, ".$1()")
            .replace(Regex("""(?<!import )java\.util\.concurrent\.ConcurrentHashMap"""), "HashMap")
            .replace("NoClassDefFoundError", "NotImplementedError")
        if (text.trim() == "@Synchronized" || text.trim() == "@Volatile") {
            ""
        } else {
            text.replace("@Synchronized ", "").replace("@Volatile ", "")
        }
    }
}

kotlin {
    // Two halves. commonMain is pure Kotlin, read from src/pureMain/kotlin: no JDK, no TDLib, no
    // DataStore, so it compiles for the browser too (web/, CP92). Everything else is the jvmShared
    // group, android plus jvm, which still reads src/commonMain/kotlin as ordinary JVM Kotlin
    // (java.io.File, HttpURLConnection and the rest), so no file had to move at once. A file moves
    // to pureMain once it stops needing the JDK; the plan and what blocks the rest are in
    // docs/WEB.md ("The core's web target").
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }
    android {
        namespace = "com.tmplayer.core"
        // tdl-coroutines refuses anything older, as in :app.
        compileSdk = 37
        // As low as :app, which says why; :app desugars java.time and java.nio.file for this code.
        minSdk = 23
    }
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf("src/pureMain/kotlin"))
            kotlin.srcDir(generateStrings)
            resources.setSrcDirs(emptyList<String>())
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // @Immutable and @Stable only, which the browser build needs no runtime for.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.runtime.annotation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmSharedMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            resources.srcDir("src/commonMain/resources")
            dependencies {
                api(libs.tdl.coroutines)
                api(libs.androidx.datastore.preferences.core)
                implementation(libs.androidx.compose.runtime)
                // The shared half of crash reporting (CrashRecord, CrashReportScrubber, CrashLimits),
                // which builds and trims Sentry's events with its protocol classes. Each app brings
                // the SDK up itself: Android through sentry-android-core, which is built on this very
                // artifact at the same version, and the desktop directly.
                api(libs.sentry)
            }
        }
        wasmJsMain {
            kotlin.srcDir(fetchTdlSources)
            kotlin.srcDir(coreWebSources)
            dependencies {
                implementation(libs.kotlinx.atomicfu)
                api(libs.androidx.datastore.preferences.core)
            }
        }
        jvmMain.dependencies {
            // Android ships org.json in the platform, and bundling it there would shadow the
            // framework's copy; a plain JVM has none, so only this target carries it.
            implementation(libs.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // Reads back the pure QR encoder's codes (QrCodeTest).
            implementation(libs.zxing.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The Compose compiler only records stability for :app's and :ui's screens, which are JVM; the
// browser build compiles the pure half without it.
composeCompiler {
    targetKotlinPlatforms.set(
        setOf(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
        ),
    )
}

// The same bytecode level as `:app`. A toolchain would be tidier, but the build machines run a
// newer JDK and have no 17 to provision, so the target is pinned instead.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
