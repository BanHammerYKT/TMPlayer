import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// TMPlayer in the browser (CP92, web.tmplayer.org; the plan is docs/WEB.md). Kotlin/Wasm with
// Compose Multiplatform for Web, the shared core's pure half, and TDLib's own browser build
// (tdweb) behind a small JavaScript bridge. The output is a static site: no server of ours is in
// the path, the page talks to Telegram itself.
//
//   ./gradlew :web:wasmJsBrowserDevelopmentRun   http://localhost:8080/
//   ./gradlew :web:wasmJsBrowserDistribution     web/build/dist/wasmJs/productionExecutable
//
// Run the two in separate Gradle invocations: Kotlin's development and production syncs share a
// directory, and Gradle stops on the implicit dependency between them.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val webVersion: String = providers.gradleProperty("webVersion").getOrElse("1.0.0-dev")

// The same Telegram API credentials as the apps (local.properties, else the environment, which
// is how CI passes the repository secrets). They end up in the page's script, exactly as they
// are in the APK. A build without them still runs and says so on the sign in screen.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name).orEmpty()

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val apiId = secret("TG_API_ID").ifBlank { "0" }
    val apiHash = secret("TG_API_HASH")
    val tmdbKey = secret("TMDB_API_KEY")
    inputs.property("apiId", apiId)
    inputs.property("apiHash", apiHash)
    inputs.property("tmdbKey", tmdbKey)
    inputs.property("version", webVersion)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/tmplayer/web").apply { mkdirs() }
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package com.tmplayer.web
            |
            |internal object BuildInfo {
            |    const val VERSION = "$webVersion"
            |    const val TG_API_ID = $apiId
            |    const val TG_API_HASH = "$apiHash"
            |    const val TMDB_API_KEY = "$tmdbKey"
            |}
            |""".trimMargin(),
        )
    }
}

// tdweb: TDLib 1.8.64 built for the browser by DIBgram/tdweb's public Actions workflow, taken at
// one commit and checked file by file against the SHA-256 below (docs/WEB.md, "Telegram: tdweb").
// A file that does not match fails the build; nothing unpinned is ever served. The worker and the
// wasm are found relative to the page, so they go to the site root beside index.html.
val tdwebCommit = "af27a3b79f6dc7435d5ceeb31a998d531d845762"
val tdwebFiles = mapOf(
    "tdweb.js" to "747c02108e49b924c23bb556b40d203f11261c9e0bd419ba479ccd56e143d709",
    "8a2ea5aea50674d967ce.worker.js" to "cc0789d76ddb74f647f0a6cc4c70420846f7792a117b251fb902a521cb7cae5e",
    "1.8a2ea5aea50674d967ce.worker.js" to "eda4196bdad3d40fbe47a5a9cc75974f7204c497634cbfd1aff971ca0858c56e",
    "b351db12a104e44cf97b09a3385b7077.wasm" to "33b823d03c4b7c59f82ca7527232d874ea3b28d52bebf738827e78cdfd6c7a98",
)

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

val fetchTdweb = tasks.register("fetchTdweb") {
    val out = layout.buildDirectory.dir("tdweb")
    inputs.property("commit", tdwebCommit)
    inputs.property("files", tdwebFiles)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { mkdirs() }
        for ((name, hash) in tdwebFiles) {
            val target = dir.resolve(name)
            if (target.exists() && sha256(target.readBytes()) == hash) continue
            val url = "https://raw.githubusercontent.com/DIBgram/tdweb/$tdwebCommit/dist/$name"
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val got = sha256(bytes)
            if (got != hash) throw GradleException("tdweb: $name has SHA-256 $got, expected $hash")
            target.writeBytes(bytes)
        }
    }
}

// The UI catalogs the apps use, served as they are and fetched by the page for the chosen
// language (the English one is compiled in through :core's generated Strings as well).
val copyCatalogs = tasks.register<Sync>("copyCatalogs") {
    from(rootProject.file("core/src/commonMain/resources/i18n"))
    into(layout.buildDirectory.dir("catalogs/i18n"))
}

// The Noto subsets the desktop ships for CJK, Indic and Arabic text, reused rather than copied
// into this module; the Latin, Cyrillic and Greek face and the emoji live in web/fonts.
val copyFonts = tasks.register<Sync>("copyFonts") {
    from(rootProject.file("ui/src/jvmMain/resources/fonts")) { include("*.ttf") }
    from(layout.projectDirectory.dir("fonts")) { include("*.ttf") }
    into(layout.buildDirectory.dir("fonts/fonts"))
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("tmweb")
        browser {
            commonWebpackConfig {
                outputFileName = "tmweb.js"
            }
        }
        binaries.executable()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    sourceSets {
        wasmJsMain {
            kotlin.srcDir(generateBuildInfo)
            resources.srcDir(fetchTdweb)
            resources.srcDir(copyCatalogs.map { layout.buildDirectory.dir("catalogs").get() })
            resources.srcDir(copyFonts.map { layout.buildDirectory.dir("fonts").get() })
            dependencies {
                implementation(project(":core"))
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.material3)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.browser)
            }
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

