// java.nio.file.Path as far as the shared code compares places with it: a path as its parts.
package java.nio.file

class Path internal constructor(private val parts: List<String>) {
    fun startsWith(other: Path): Boolean = parts.size >= other.parts.size && parts.subList(0, other.parts.size) == other.parts

    override fun equals(other: Any?): Boolean = other is Path && other.parts == parts
    override fun hashCode(): Int = parts.hashCode()
    override fun toString(): String = parts.joinToString("/", prefix = "/")

    companion object {
        fun of(text: String): Path = Path(text.split('/').filter { it.isNotEmpty() && it != "." })
    }
}

enum class StandardCopyOption { REPLACE_EXISTING, ATOMIC_MOVE, COPY_ATTRIBUTES }

/** java.nio.file.Files over the browser build's in-memory files (java.io.File). */
object Files {
    fun move(from: Path, to: Path, vararg options: StandardCopyOption): Path {
        java.io.File(from.toString()).renameTo(java.io.File(to.toString()))
        return to
    }

    fun copy(from: Path, to: Path, vararg options: StandardCopyOption): Path {
        java.io.File(to.toString()).writeBytes(java.io.File(from.toString()).readBytes())
        return to
    }
}
