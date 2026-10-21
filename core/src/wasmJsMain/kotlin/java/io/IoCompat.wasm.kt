// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package java.io

typealias IOException = com.tmplayer.platform.IOException

open class EOFException(message: String? = null) : com.tmplayer.platform.IOException(message)

open class FileNotFoundException(message: String? = null) : com.tmplayer.platform.IOException(message)

interface Closeable {
    fun close()
}

/**
 * A file name. The browser build keeps nothing in files of its own (TDLib's files live in
 * tdweb's storage, settings in the browser's), so a File here names a place and holds what is
 * written to it for as long as the page is open.
 */
class File(val path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null || parent.path.isEmpty()) child else parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String, child: String) : this(File(parent), child)

    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val absolutePath: String get() = path
    val absoluteFile: File get() = this
    val parentFile: File? get() = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::File)
    val parent: String? get() = parentFile?.path
    val isFile: Boolean get() = path in contents
    val isDirectory: Boolean get() = path in dirs
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")

    fun exists(): Boolean = isFile || isDirectory
    fun length(): Long = contents[path]?.size?.toLong() ?: 0L
    fun lastModified(): Long = 0L
    fun mkdirs(): Boolean {
        dirs += path
        return true
    }
    fun mkdir(): Boolean = mkdirs()
    fun delete(): Boolean = contents.remove(path) != null || dirs.remove(path)
    fun deleteRecursively(): Boolean {
        contents.keys.filter { it == path || it.startsWith("$path/") }.forEach { contents.remove(it) }
        dirs.removeAll { it == path || it.startsWith("$path/") }
        return true
    }
    fun listFiles(): Array<File>? = if (!isDirectory) null else
        (contents.keys + dirs).filter { it.startsWith("$path/") && '/' !in it.removePrefix("$path/") }.map(::File).toTypedArray()
    fun list(): Array<String>? = listFiles()?.map { it.name }?.toTypedArray()
    fun readBytes(): ByteArray = contents[path] ?: throw FileNotFoundException(path)
    fun readText(): String = readBytes().decodeToString()
    fun writeBytes(bytes: ByteArray) {
        contents[path] = bytes.copyOf()
    }
    fun writeText(text: String) = writeBytes(text.encodeToByteArray())
    fun renameTo(dest: File): Boolean {
        val bytes = contents.remove(path) ?: return false
        contents[dest.path] = bytes
        return true
    }
    fun resolve(child: String): File = File(this, child)
    fun usableSpace(): Long = 0L
    val usableSpace: Long get() = 0L
    val totalSpace: Long get() = 0L
    val freeSpace: Long get() = 0L

    override fun compareTo(other: File): Int = path.compareTo(other.path)
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path

    private companion object {
        val contents = HashMap<String, ByteArray>()
        val dirs = HashSet<String>()
    }
}

/** A stream nothing reads in the browser build: the JVM code that opens one is never reached there. */
open class InputStream {
    open fun read(): Int = -1
    open fun close() {}
    fun readBytes(): ByteArray = ByteArray(0)
    fun bufferedReader(): Reader = Reader()
}

open class OutputStream {
    open fun write(b: Int) {}
    open fun write(bytes: ByteArray) {}
    open fun flush() {}
    open fun close() {}
}

class Reader {
    fun readText(): String = ""
    fun close() {}
}
