package com.fengeon.resprite_desktop.format

import java.io.ByteArrayOutputStream

/**
 * Minimal GIF89a encoder written from scratch so animation export needs no new
 * Gradle dependency (the CI build must stay reproducible offline).
 *
 * Supports what pixel-art animation needs and nothing else:
 *  - one global colour table built from the colours actually present;
 *  - a single transparent index (0), so sprite edges stay clean;
 *  - per-frame delay in centiseconds;
 *  - infinite looping via the NETSCAPE2.0 extension.
 *
 * Colours beyond 255 unique values are quantised to 5 bits per channel. Pixel
 * art normally has far fewer, so the table is usually exact.
 */
object GifEncoder {

    private const val TRANSPARENT = 0

    /**
     * @param width  canvas width in pixels
     * @param height canvas height in pixels
     * @param frames ARGB pixels, row-major, one array per frame
     * @param delaysCs per-frame delay in hundredths of a second (min 2)
     */
    fun encode(width: Int, height: Int, frames: List<IntArray>, delaysCs: List<Int>): ByteArray {
        require(frames.isNotEmpty()) { "no frames" }
        require(frames.all { it.size == width * height }) { "frame size mismatch" }

        val palette = buildPalette(frames)
        val out = ByteArrayOutputStream()

        // ---- header + logical screen descriptor ----
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        val tableSize = palette.size
        val sizeBits = when {
            tableSize <= 2 -> 0
            tableSize <= 4 -> 1
            tableSize <= 8 -> 2
            tableSize <= 16 -> 3
            tableSize <= 32 -> 4
            tableSize <= 64 -> 5
            tableSize <= 128 -> 6
            else -> 7
        }
        out.write(0x80 or (0x70) or sizeBits) // GCT present, 8-bit colour resolution
        out.write(TRANSPARENT)              // background colour index
        out.write(0)                        // pixel aspect ratio

        // ---- global colour table, padded to 2^(sizeBits+1) entries ----
        val entries = 1 shl (sizeBits + 1)
        for (i in 0 until entries) {
            val c = palette.getOrNull(i) ?: 0
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }

        // ---- NETSCAPE2.0 looping extension ----
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03); out.write(0x01)
        writeShort(out, 0) // 0 = loop forever
        out.write(0x00)

        // ---- frames ----
        val lookup = HashMap<Int, Int>()
        palette.forEachIndexed { index, rgb -> if (index != TRANSPARENT) lookup.putIfAbsent(rgb, index) }
        val minCodeSize = (sizeBits + 1).coerceAtLeast(2)

        frames.forEachIndexed { i, pixels ->
            val delay = (delaysCs.getOrNull(i) ?: 10).coerceAtLeast(2)

            // Graphic control: disposal "restore to background" so frames do not smear,
            // transparent index 0.
            out.write(0x21); out.write(0xF9); out.write(0x04)
            out.write(0b000_010_0_1) // disposal=2, transparent flag on
            writeShort(out, delay)
            out.write(TRANSPARENT)
            out.write(0x00)

            // Image descriptor: full frame, no local table.
            out.write(0x2C)
            writeShort(out, 0); writeShort(out, 0)
            writeShort(out, width); writeShort(out, height)
            out.write(0x00)

            val indices = ByteArray(pixels.size)
            for (p in pixels.indices) {
                val argb = pixels[p]
                if ((argb ushr 24) and 0xFF < 128) {
                    indices[p] = TRANSPARENT.toByte()
                } else {
                    val rgb = quantise(argb and 0xFFFFFF)
                    indices[p] = (lookup[rgb] ?: nearest(palette, rgb)).toByte()
                }
            }
            writeLzw(out, indices, minCodeSize)
        }

        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    // ---- palette ---------------------------------------------------------

    private fun buildPalette(frames: List<IntArray>): List<Int> {
        val exact = LinkedHashSet<Int>()
        for (f in frames) for (p in f) {
            if ((p ushr 24) and 0xFF < 128) continue
            exact.add(p and 0xFFFFFF)
            if (exact.size > 255) break
        }
        if (exact.size <= 255) return listOf(0) + exact.toList()

        // Too many colours: bucket to 5 bits per channel and keep the first 255.
        val coarse = LinkedHashSet<Int>()
        for (f in frames) for (p in f) {
            if ((p ushr 24) and 0xFF < 128) continue
            coarse.add(quantise(p and 0xFFFFFF))
            if (coarse.size > 255) break
        }
        return listOf(0) + coarse.toList()
    }

    private fun quantise(rgb: Int): Int {
        val r = ((rgb shr 16) and 0xFF) and 0xF8
        val g = ((rgb shr 8) and 0xFF) and 0xF8
        val b = (rgb and 0xFF) and 0xF8
        return (r shl 16) or (g shl 8) or b
    }

    private fun nearest(palette: List<Int>, rgb: Int): Int {
        var best = 1
        var bestDist = Int.MAX_VALUE
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        for (i in 1 until palette.size) {
            val c = palette[i]
            val dr = r - ((c shr 16) and 0xFF)
            val dg = g - ((c shr 8) and 0xFF)
            val db = b - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    // ---- LZW -------------------------------------------------------------

    private fun writeLzw(out: ByteArrayOutputStream, indices: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var nextCode = endCode + 1
        val dict = HashMap<Long, Int>()

        val block = ByteArrayOutputStream()
        var bitBuffer = 0
        var bitCount = 0

        fun flushBlock() {
            if (block.size() == 0) return
            val bytes = block.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val chunk = minOf(255, bytes.size - offset)
                out.write(chunk)
                out.write(bytes, offset, chunk)
                offset += chunk
            }
            out.write(0)
            block.reset()
        }

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
                if (block.size() >= 255) flushBlock()
            }
        }

        emit(clearCode)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix.toLong() shl 8) or k.toLong()
            val found = dict[key]
            if (found != null) {
                prefix = found
            } else {
                emit(prefix)
                if (nextCode < 4096) {
                    dict[key] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    emit(clearCode)
                    dict.clear()
                    codeSize = minCodeSize + 1
                    nextCode = endCode + 1
                }
                prefix = k
            }
        }
        emit(prefix)
        emit(endCode)

        if (bitCount > 0) {
            block.write(bitBuffer and 0xFF)
            if (block.size() >= 255) flushBlock()
        }
        flushBlock()
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
