pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Binaryen, which optimises the browser build's wasm (web/, CP92). Kotlin would add this
        // repository to the project itself, which FAIL_ON_PROJECT_REPOS refuses, so it is declared
        // here (and BinaryenEnvSpec.downloadBaseUrl is cleared in the root build). Only that one
        // module comes from it.
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen"
                    patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "TMPlayer"
include(":app")
include(":core")
include(":ui")
include(":desktop")
include(":web")
