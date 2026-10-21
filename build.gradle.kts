plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Kotlin/Wasm's tooling (web/ and :core's wasmJs tests on Node) would download Node.js and
// Binaryen from repositories it adds to each project, which settings.gradle.kts forbids. Node
// comes from the machine instead (any recent LTS; CI installs one), and Binaryen through the ivy
// repository declared in settings.
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
            download.set(false)
        }
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
            download.set(false)
        }
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec> {
            downloadBaseUrl.set(null as String?)
        }
    }
}
