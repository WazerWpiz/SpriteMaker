package com.fengeon.resprite_desktop.format

import android.graphics.Color
import com.fengeon.resprite_desktop.model.Palette
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * GIMP palette (.gpl) and Resprite palette (.rpl) reader/writer.
 *
 * .gpl format:
 *   GIMP Palette
 *   Name: Foo
 *   Columns: 8
 *   #
 *   255   0   0\tRed
 *
 * .rpl is Resprite's own format, structurally the same list of RGB triplets
 * with a small header. We write GIMP-compatible output for both extensions;
 * the original app reads .rpl as RGB lines too.
 */
object PaletteIO {

    fun readGpl(input: InputStream): Palette {
        val lines = input.bufferedReader().readLines()
        var name = "Imported"
        val colors = ArrayList<Int>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("GIMP Palette", ignoreCase = true)) continue
            if (line.startsWith("Name:", ignoreCase = true)) {
                name = line.substringAfter(':').trim()
                continue
            }
            if (line.startsWith("Columns:", ignoreCase = true)) continue
            if (line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 4)
            if (parts.size < 3) continue
            val r = parts[0].toIntOrNull() ?: continue
            val g = parts[1].toIntOrNull() ?: continue
            val b = parts[2].toIntOrNull() ?: continue
            colors.add(Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)))
        }
        return Palette(name, colors)
    }

    fun writeGpl(palette: Palette, out: OutputStream) {
        out.bufferedWriter().use { w ->
            w.write("GIMP Palette\n")
            w.write("Name: ${palette.name}\n")
            w.write("Columns: 8\n")
            w.write("#\n")
            for (c in palette.colors) {
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                w.write(String.format("%3d %3d %3d\n", r, g, b))
            }
        }
    }

    fun readRpl(input: InputStream): Palette = readGpl(input)

    fun writeRpl(palette: Palette, out: OutputStream) = writeGpl(palette, out)

    fun readFile(file: File): Palette = when (file.extension.lowercase()) {
        "rpl" -> file.inputStream().use(::readRpl)
        else -> file.inputStream().use(::readGpl)
    }
}
