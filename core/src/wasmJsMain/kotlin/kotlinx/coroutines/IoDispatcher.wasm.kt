// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package kotlinx.coroutines

/** The JVM's IO dispatcher; the browser runs everything on its one thread. */
val Dispatchers.IO: CoroutineDispatcher get() = Dispatchers.Default
