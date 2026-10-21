package com.tmplayer.data

/**
 * A QR code for a short text, as a square of dark and light modules, in pure Kotlin.
 *
 * The phone and the desktop draw theirs with ZXing, which is JVM only; the browser build needs one
 * of its own, so this encodes byte mode at a chosen error correction level, picks the smallest
 * version that fits and the mask with the lowest penalty, as the standard (ISO/IEC 18004) says.
 * The sign in link Telegram hands out (`tg://login?token=...`, about 70 characters) lands on
 * version 4 or 5. QrCodeTest decodes the result with ZXing to check it.
 */
class QrCode private constructor(val size: Int, private val modules: BooleanArray) {

    /** Whether the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]

    enum class Ecc(internal val ordinal2: Int, internal val formatBits: Int) {
        Low(0, 1), Medium(1, 0), Quartile(2, 3), High(3, 2)
    }

    companion object {
        /** [text] encoded as UTF-8 bytes, or null when it does not fit even version 40. */
        fun encode(text: String, ecc: Ecc = Ecc.Medium): QrCode? = encode(text.encodeToByteArray(), ecc)

        fun encode(data: ByteArray, ecc: Ecc = Ecc.Medium): QrCode? {
            var version = 1
            while (true) {
                val capacity = dataCodewords(version, ecc) * 8
                val used = 4 + (if (version <= 9) 8 else 16) + data.size * 8
                if (used <= capacity) break
                version++
                if (version > 40) return null
            }
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, if (version <= 9) 8 else 16)
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            val capacity = dataCodewords(version, ecc) * 8
            bits.append(0, minOf(4, capacity - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            var pad = 0xEC
            while (bits.length < capacity) {
                bits.append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            val codewords = ByteArray(bits.length / 8)
            for (i in 0 until bits.length) {
                if (bits[i]) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
            return Builder(version, ecc).build(interleave(codewords, version, ecc))
        }

        private fun interleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val blocks = NUM_BLOCKS[ecc.ordinal2][version]
            val eccLen = ECC_PER_BLOCK[ecc.ordinal2][version]
            val raw = rawModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = rsDivisor(eccLen)
            val parts = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until blocks) {
                val dataLen = shortLen - eccLen + (if (i < shortBlocks) 0 else 1)
                val chunk = data.copyOfRange(k, k + dataLen)
                k += dataLen
                val block = ByteArray(shortLen + 1)
                chunk.copyInto(block)
                val remainder = rsRemainder(chunk, divisor)
                remainder.copyInto(block, shortLen + 1 - eccLen)
                parts += block
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0..shortLen) {
                for (j in parts.indices) {
                    if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = parts[j][i]
                }
            }
            return out
        }

        private fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1, result.size)
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        internal fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int, ecc: Ecc): Int =
            rawModules(version) / 8 - ECC_PER_BLOCK[ecc.ordinal2][version] * NUM_BLOCKS[ecc.ordinal2][version]

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )

        private val NUM_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }

    private class BitBuffer {
        private var bits = BooleanArray(256)
        var length = 0
            private set

        operator fun get(i: Int) = bits[i]

        fun append(value: Int, count: Int) {
            if (length + count > bits.size) bits = bits.copyOf(maxOf(bits.size * 2, length + count))
            for (i in count - 1 downTo 0) bits[length++] = (value ushr i) and 1 != 0
        }
    }

    private class Builder(private val version: Int, private val ecc: Ecc) {
        val size = version * 4 + 17
        val dark = BooleanArray(size * size)
        val function = BooleanArray(size * size)

        private fun set(x: Int, y: Int, on: Boolean) {
            dark[y * size + x] = on
            function[y * size + x] = true
        }

        fun build(codewords: ByteArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) {
                    bestPenalty = p
                    best = mask
                }
                applyMask(mask)
            }
            applyMask(best)
            drawFormat(best)
            return QrCode(size, dark)
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val align = alignmentPositions()
            val n = align.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                alignment(align[i], align[j])
            }
            drawFormat(0)
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) {
                set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var pos = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun drawFormat(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val on = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, on)
                set(b, a, on)
            }
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y * size + x] && i < data.size * 8) {
                            dark[y * size + x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                val k = y * size + x
                if (invert && !function[k]) dark[k] = !dark[k]
            }
        }

        private fun at(x: Int, y: Int) = dark[y * size + x]

        private fun penalty(): Int {
            var result = 0
            // Runs of five or more in a row or a column.
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    var run = 0
                    var color = false
                    for (b in 0 until size) {
                        val c = if (horizontal) at(b, a) else at(a, b)
                        if (b > 0 && c == color) {
                            run++
                        } else {
                            if (run >= 5) result += run - 2
                            color = c
                            run = 1
                        }
                    }
                    if (run >= 5) result += run - 2
                }
            }
            // Two by two blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = at(x, y)
                if (c == at(x + 1, y) && c == at(x, y + 1) && c == at(x + 1, y + 1)) result += 3
            }
            // Finder-like patterns: dark light dark dark dark light dark with four light on a side.
            val pattern = booleanArrayOf(true, false, true, true, true, false, true)
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    for (b in 0..size - 7) {
                        var match = true
                        for (k in 0 until 7) {
                            val c = if (horizontal) at(b + k, a) else at(a, b + k)
                            if (c != pattern[k]) {
                                match = false
                                break
                            }
                        }
                        if (!match) continue
                        fun light(from: Int, to: Int): Boolean {
                            for (k in from until to) {
                                if (k < 0 || k >= size) continue
                                val c = if (horizontal) at(k, a) else at(a, k)
                                if (c) return false
                            }
                            return true
                        }
                        if (light(b - 4, b) || light(b + 7, b + 11)) result += 40
                    }
                }
            }
            // Balance of dark and light.
            val darkCount = dark.count { it }
            val total = size * size
            val k = (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }
    }
}
