// java.math.BigDecimal as far as the player's speed label uses it: a number rounded to a few
// places, then handed to the locale's number formatter.
package java.math

enum class RoundingMode { HALF_UP }

class BigDecimal(private val value: Double) : Number() {
    @Suppress("UNUSED_PARAMETER")
    fun setScale(places: Int, mode: RoundingMode): BigDecimal {
        var scale = 1.0
        repeat(places) { scale *= 10 }
        return BigDecimal(kotlin.math.floor(value * scale + 0.5) / scale)
    }

    fun stripTrailingZeros(): BigDecimal = this

    override fun toDouble(): Double = value
    override fun toFloat(): Float = value.toFloat()
    override fun toLong(): Long = value.toLong()
    override fun toInt(): Int = value.toInt()
    override fun toShort(): Short = value.toInt().toShort()
    override fun toByte(): Byte = value.toInt().toByte()
    override fun toString(): String = value.toString()
}
