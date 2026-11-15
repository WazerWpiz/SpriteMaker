package com.fengeon.resprite_desktop.format

import com.fengeon.resprite_desktop.model.Document
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .resprite container:
 *   tmp_<UUID>.resprite/document.json
 *   tmp_<UUID>.resprite/meta.json
 *   tmp_<UUID>.resprite/preview.png
 *   tmp_<UUID>.resprite/thumbnail.png
 *   tmp_<UUID>.resprite/CellData/<cellUUID>.png
 *   tmp_<UUID>.resprite/ReferenceImages/...
 *
 * CellData PNGs are stored as raw RGBA rasters sized to the cell bounds.
 */
object RespriteFile {

    private const val DOC = "document.json"
    private const val META = "meta.json"
    private const val PREVIEW = "preview.png"
    private const val THUMB = "thumbnail.png"
    private const val CELLS = "CellData"
    private const val REFS = "ReferenceImages"

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    data class Contents(
        val rootDir: String,
        val document: Document,
        val cells: Map<String, ByteArray>,
        val preview: ByteArray?,
        val thumbnail: ByteArray?,
        val meta: Map<String, String>
    )

    fun read(input: InputStream): Contents {
        var rootDir: String? = null
        var docBytes: ByteArray? = null
        var metaBytes: ByteArray? = null
        var preview: ByteArray? = null
        var thumb: ByteArray? = null
        val cells = LinkedHashMap<String, ByteArray>()

        ZipInputStream(input.buffered()).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory) {
                    val bytes = zis.readBytes()
                    when {
                        name.endsWith("/$DOC") -> {
                            rootDir = name.substringBeforeLast("/")
                            docBytes = bytes
                        }
                        name.endsWith("/$META") -> metaBytes = bytes
                        name.endsWith("/$PREVIEW") -> preview = bytes
                        name.endsWith("/$THUMB") -> thumb = bytes
                        name.contains("/$CELLS/") && name.endsWith(".png") -> {
                            val id = name.substringAfterLast('/').removeSuffix(".png")
                            cells[id] = bytes
                        }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        requireNotNull(docBytes) { "document.json missing" }
        val document = json.decodeFromString(Document.serializer(), docBytes!!.decodeToString())
        val meta = metaBytes?.decodeToString()?.let(::parseMeta) ?: emptyMap()

        return Contents(
            rootDir = rootDir ?: "tmp_${UUID.randomUUID()}.resprite",
            document = document,
            cells = cells,
            preview = preview,
            thumbnail = thumb,
            meta = meta
        )
    }

    fun write(
        output: OutputStream,
        document: Document,
        cells: Map<String, ByteArray>,
        preview: ByteArray?,
        thumbnail: ByteArray?
    ) {
        val root = "tmp_${UUID.randomUUID()}.resprite"
        ZipOutputStream(output.buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("$root/$DOC"))
            zos.write(json.encodeToString(Document.serializer(), document).encodeToByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("$root/$META"))
            val meta = buildString {
                append('{'); append("\"appVersion\":\"R35\",")
                append("\"canvasSize\":{\"width\":"); append(document.canvasSize.width)
                append(",\"height\":"); append(document.canvasSize.height); append("},")
                append("\"frameCount\":"); append(document.frameCount); append('}')
            }
            zos.write(meta.encodeToByteArray())
            zos.closeEntry()

            preview?.let {
                zos.putNextEntry(ZipEntry("$root/$PREVIEW")); zos.write(it); zos.closeEntry()
            }
            thumbnail?.let {
                zos.putNextEntry(ZipEntry("$root/$THUMB")); zos.write(it); zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry("$root/$CELLS/")); zos.closeEntry()
            for ((id, bytes) in cells) {
                zos.putNextEntry(ZipEntry("$root/$CELLS/$id.png"))
                zos.write(bytes)
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("$root/$REFS/")); zos.closeEntry()
        }
    }

    private fun parseMeta(text: String): Map<String, String> {
        // Minimal flat parse; meta.json is small and machine-generated.
        return Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun readFile(file: File): Contents = file.inputStream().use { read(it) }

    fun writeFile(file: File, doc: Document, cells: Map<String, ByteArray>,
                  preview: ByteArray? = null, thumbnail: ByteArray? = null) {
        file.outputStream().use { write(it, doc, cells, preview, thumbnail) }
    }
}
