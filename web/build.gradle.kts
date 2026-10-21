import java.net.URI
import java.security.MessageDigest
import java.util.Base64
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
            // The network here can be slow; three tries before giving up.
            var bytes = ByteArray(0)
            for (attempt in 1..3) {
                bytes = runCatching {
                    val conn = URI(url).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }
                    conn.getInputStream().use { it.readBytes() }
                }.getOrElse { if (attempt == 3) throw it else ByteArray(0) }
                if (bytes.isNotEmpty()) break
            }
            val got = sha256(bytes)
            if (got != hash) throw GradleException("tdweb: $name has SHA-256 $got, expected $hash")
            target.writeBytes(bytes)
        }
    }
}

// tdweb's worker with sparse storage for TDLib's files directory (web/tdweb/sparse-fs.js): on
// OPFS where the browser has it, else in memory chunks with a ceiling, instead of stock MEMFS's
// dense arrays, so seeking into a 4 GB video does not allocate 4 GB. Three exact text edits over
// the verified original; a missing anchor fails the build rather than shipping half a patch.
val patchTdweb = tasks.register("patchTdweb") {
    val source = layout.buildDirectory.dir("tdweb")
    val sparse = layout.projectDirectory.file("tdweb/sparse-fs.js")
    val out = layout.buildDirectory.dir("tdweb-site")
    dependsOn(fetchTdweb)
    inputs.dir(source)
    inputs.file(sparse)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.apply { deleteRecursively(); mkdirs() }
        val src = source.get().asFile
        // The wasm is served as it is; the page script and both worker files are patched below
        // and served under new content-hashed names, since a hashed name is cached for a year
        // (_headers, sw.js) and a patched file kept under the original name would never reach a
        // browser that has the stock one.
        val stockHash = "8a2ea5aea50674d967ce"
        for (name in tdwebFiles.keys.filter { it.endsWith(".wasm") }) src.resolve(name).copyTo(dir.resolve(name), overwrite = true)
        fun hashed(text: String) = sha256(text.toByteArray()).take(20)
        val workerName = "$stockHash.worker.js"
        var worker = src.resolve(workerName).readText()
        fun edit(anchor: String, replacement: String) {
            val count = worker.split(anchor).size - 1
            if (count != 1) throw GradleException("tdweb patch: '$anchor' found $count times, expected once")
            worker = worker.replace(anchor, replacement)
        }
        // Open the disk files of the last session before TDLib's files directory is made...
        edit(
            "return ifs.idb;",
            "return Promise.all([ifs.idb, self.TmSparse.prepare()]).then(function (r) { return r[0]; });",
        )
        // ...and make that directory sparse once it exists.
        edit("ifs.FS.mkdir(root);", "ifs.FS.mkdir(root);\n                self.TmSparse.mount(ifs.FS, root);")
        // A finished video is not read whole into memory to be copied into IndexedDB: it stays
        // where it is, and is read from there in parts. Small files (thumbnails) still are.
        edit(
            "value: function saveFile(pid, file) {",
            "value: function saveFile(pid, file) {\n      if ((file.size || file.expected_size || 0) > 8388608) {\n        return file;\n      }",
        )

        // TDLib's file reads and writes past 2 GiB: the emscripten glue hands the position on as
        // its low 32 bits only, read as a signed number, so a part at 3.9 GB into a 4 GB video
        // was written to a negative position and the read waiting for it never ended. Both
        // halves are joined, as the same glue's _fd_seek already does.
        val glueName = "1.8a2ea5aea50674d967ce.worker.js"
        var glue = src.resolve(glueName).readText()
        fun editGlue(anchor: String, replacement: String) {
            val count = glue.split(anchor).size - 1
            if (count != 1) throw GradleException("tdweb patch: '$anchor' found $count times in $glueName, expected once")
            glue = glue.replace(anchor, replacement)
        }
        editGlue(
            "var num=SYSCALLS.doReadv(stream,iov,iovcnt,offset_low);",
            "var num=SYSCALLS.doReadv(stream,iov,iovcnt,offset_high*4294967296+(offset_low>>>0));",
        )
        editGlue(
            "var num=SYSCALLS.doWritev(stream,iov,iovcnt,offset_low);",
            "var num=SYSCALLS.doWritev(stream,iov,iovcnt,offset_high*4294967296+(offset_low>>>0));",
        )
        editGlue("get64:function(low,high){return low}", "get64:function(low,high){return high*4294967296+(low>>>0)}")
        val glueHash = hashed(glue)
        dir.resolve("1.$glueHash.worker.js").writeText(glue)

        // The worker loads the glue by name, and the page script the worker.
        edit("chunkId + \".\" + \"$stockHash\" + \".worker.js\"", "chunkId + \".\" + \"$glueHash\" + \".worker.js\"")
        val patchedWorker = sparse.asFile.readText() + "\n" + worker
        val workerHash = hashed(patchedWorker)
        dir.resolve("$workerHash.worker.js").writeText(patchedWorker)
        var page = src.resolve("tdweb.js").readText()
        val pageAnchor = "\"$stockHash.worker.js\""
        if (page.split(pageAnchor).size - 1 != 1) throw GradleException("tdweb patch: worker name not found once in tdweb.js")
        page = page.replace(pageAnchor, "\"$workerHash.worker.js\"")
        dir.resolve("tdweb.js").writeText(page)
    }
}

// movi-player 0.4.1 (Apache-2.0; FFmpeg inside its wasm is LGPL-2.1+, dav1d BSD-2-Clause): the
// decoder for what the browser cannot play itself (docs/WEB.md, "Playback"). The npm tarball is
// fetched at the version below and checked against the registry's SHA-512 integrity; only the
// slim element, its wasm and the licence are served, under player/movi/, and only loaded when a
// video needs it.
val moviVersion = "0.4.1"
val moviIntegrity = "sha512-oA91s8nxyt7tnd7SaZNKVkjkz8UeHVrcZNxWQSBo5Jj/MOog7pnGtwv0edimjwocJkZVBBtBMy8Ef4HOGzBJGQ=="

val fetchMovi = tasks.register("fetchMovi") {
    val out = layout.buildDirectory.file("movi/movi-player-$moviVersion.tgz")
    inputs.property("version", moviVersion)
    inputs.property("integrity", moviIntegrity)
    outputs.file(out)
    doLast {
        val target = out.get().asFile
        fun check(bytes: ByteArray): Boolean =
            "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes)) == moviIntegrity
        if (target.exists() && check(target.readBytes())) return@doLast
        val url = "https://registry.npmjs.org/movi-player/-/movi-player-$moviVersion.tgz"
        var bytes = ByteArray(0)
        for (attempt in 1..3) {
            bytes = runCatching {
                URI(url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 300_000
                }.getInputStream().use { it.readBytes() }
            }.getOrElse { if (attempt == 3) throw it else ByteArray(0) }
            if (bytes.isNotEmpty()) break
        }
        if (!check(bytes)) throw GradleException("movi-player $moviVersion does not match its integrity hash")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }
}

val moviSite = tasks.register<Sync>("moviSite") {
    dependsOn(fetchMovi)
    from(tarTree(resources.gzip(layout.buildDirectory.file("movi/movi-player-$moviVersion.tgz")))) {
        include("package/dist/element.slim.js", "package/dist/movi.wasm", "package/LICENSE")
        eachFile { path = "player/movi/" + name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("movi-site"))
}

// The player layer (web/player), served under player/.
val playerSite = tasks.register<Sync>("playerSite") {
    from(layout.projectDirectory.dir("player")) { into("player") }
    into(layout.buildDirectory.dir("player-site"))
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

// The desktop app's own screens and player controls (desktop/src/main/kotlin), compiled for the
// browser so a wide window gets exactly the desktop's UI (web/desktop-sources.txt lists them).
// The JVM spellings with a plain Kotlin twin are rewritten in the copy, as :core and :ui do; what
// is the desktop's alone (mpv, the window, files, the tray, the updater) has a browser stand in
// under web/src/wasmJsMain/kotlin/com/tmplayer/desktop.
val desktopWebSources = tasks.register<Sync>("desktopWebSources") {
    val list = layout.projectDirectory.file("desktop-sources.txt")
    inputs.file(list)
    val names = list.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    from(rootProject.layout.projectDirectory.dir("desktop/src/main/kotlin")) { include(names) }
    into(layout.buildDirectory.dir("desktop-web/kotlin"))
    val localeCase = Regex("""\.(lowercase|uppercase)\((java\.util\.)?Locale\.(ROOT|US|ENGLISH)\)""")
    filter { line ->
        val text = line
            .replace("Dispatchers.IO", "Dispatchers.Default")
            .replace("Dispatchers.Swing", "Dispatchers.Main")
            .replace(localeCase, ".$1()")
        val t = text.trim()
        if (t == "@Synchronized" || t == "@Volatile" || t == "import kotlinx.coroutines.runBlocking" || t == "import kotlinx.coroutines.swing.Swing") {
            ""
        } else {
            text.replace("@Synchronized ", "").replace("@Volatile ", "")
        }
    }
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
            kotlin.srcDir(desktopWebSources)
            resources.srcDir(patchTdweb)
            resources.srcDir(moviSite)
            resources.srcDir(playerSite)
            resources.srcDir(copyCatalogs.map { layout.buildDirectory.dir("catalogs").get() })
            resources.srcDir(copyFonts.map { layout.buildDirectory.dir("fonts").get() })
            dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
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

