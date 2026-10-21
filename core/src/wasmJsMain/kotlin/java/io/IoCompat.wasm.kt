// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package java.io

/** The JDK's IOException: a kind of the platform's, so either catch sees what the other throws here. */
open class IOException(message: String? = null) : com.tmplayer.platform.IOException(message)

open class EOFException(message: String? = null) : com.tmplayer.platform.IOException(message)

open class FileNotFoundException(message: String? = null) : com.tmplayer.platform.IOException(message)

interface Closeable : AutoCloseable {
    override fun close()
}

/**
 * A file name. The browser build keeps nothing in files of its own (TDLib's files live in
 * tdweb's storage, settings in the browser's), so a File here names a place and holds what is
 * written to it for as long as the page is open.
 */
open class File(val path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null || parent.path.isEmpty()) child else parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String, child: String) : this(File(parent), child)

    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val absolutePath: String get() = path
    val absoluteFile: File get() = this
    val canonicalPath: String get() = path
    val canonicalFile: File get() = this
    fun normalize(): File = this
    fun toPath(): java.nio.file.Path = java.nio.file.Path.of(path)
    fun isAbsolute(): Boolean = path.startsWith("/")
    fun canWrite(): Boolean = true
    fun walkTopDown(): Sequence<File> = sequenceOf(this) + (contents.keys + dirs).filter { it.startsWith("$path/") }.map(::File).asSequence()
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
    fun inputStream(): InputStream = ByteArrayInputStream(readBytes())
    fun outputStream(): OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            writeBytes(toByteArray())
        }
    }
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

/** A byte stream; in the browser build only ever over bytes already in memory. */
open class InputStream : Closeable {
    open fun read(): Int = -1
    override fun close() {}
    open fun readBytes(): ByteArray {
        val out = ArrayList<Byte>()
        while (true) {
            val b = read()
            if (b < 0) break
            out += b.toByte()
        }
        return out.toByteArray()
    }
    fun bufferedReader(): Reader = Reader(readBytes().decodeToString())
}

open class ByteArrayInputStream(private val bytes: ByteArray) : InputStream() {
    private var at = 0
    override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
    override fun readBytes(): ByteArray = bytes.copyOfRange(at, bytes.size).also { at = bytes.size }
}

open class OutputStream : Closeable {
    open fun write(b: Int) {}
    open fun write(bytes: ByteArray) {
        bytes.forEach { write(it.toInt()) }
    }
    open fun flush() {}
    override fun close() {}
}

open class ByteArrayOutputStream : OutputStream() {
    private val buffer = ArrayList<Byte>()
    override fun write(b: Int) {
        buffer += b.toByte()
    }
    fun toByteArray(): ByteArray = buffer.toByteArray()
}

class Reader(private val text: String = "") : Closeable {
    fun readText(): String = text
    fun readLines(): List<String> = text.lines()
    override fun close() {}
}
