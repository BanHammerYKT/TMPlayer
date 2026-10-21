// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package java.util.concurrent.atomic

class AtomicLong(private var value: Long = 0L) {
    fun get(): Long = value
    fun set(v: Long) {
        value = v
    }
    fun incrementAndGet(): Long = ++value
    fun getAndIncrement(): Long = value++
    fun addAndGet(delta: Long): Long {
        value += delta
        return value
    }
    fun compareAndSet(expect: Long, update: Long): Boolean {
        if (value != expect) return false
        value = update
        return true
    }
}

class AtomicInteger(private var value: Int = 0) {
    fun get(): Int = value
    fun set(v: Int) {
        value = v
    }
    fun incrementAndGet(): Int = ++value
    fun getAndIncrement(): Int = value++
    fun decrementAndGet(): Int = --value
    fun getAndDecrement(): Int = value--
    fun addAndGet(delta: Int): Int {
        value += delta
        return value
    }
}

class AtomicBoolean(private var value: Boolean = false) {
    fun get(): Boolean = value
    fun set(v: Boolean) {
        value = v
    }
    fun getAndSet(v: Boolean): Boolean {
        val old = value
        value = v
        return old
    }
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        if (value != expect) return false
        value = update
        return true
    }
}

class AtomicReference<T>(private var value: T) {
    fun get(): T = value
    fun set(v: T) {
        value = v
    }
    fun getAndSet(v: T): T {
        val old = value
        value = v
        return old
    }
}
