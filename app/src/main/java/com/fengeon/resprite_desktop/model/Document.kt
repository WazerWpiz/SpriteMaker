package com.fengeon.resprite_desktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root of the .resprite scene graph. Field names match document.json exactly
 * so a read -> write -> read round trip is byte-stable for the fields we own.
 */
@Serializable
data class Document(
    val canvasSize: Size = Size(64, 64),
    val gridWidth: Int = 16,
    val gridHeight: Int = 16,
    val isoGridHeight: Int = 16,
    val frameRate: Int = 12,
    val frameCount: Int = 1,
    val currFrameNo: Int = 0,
    val currLayerNo: Int = 0,
    val isPaletteLocked: Boolean = false,
    val showGrid: Boolean = false,
    val showIsoGrid: Boolean = false,
    val isSymmetricToolHorizontalOn: Boolean = false,
    val isSymmetricToolVerticalOn: Boolean = false,
    val symmetricToolPosHorizontal: Int = 0,
    val symmetricToolPosVertical: Int = 0,
    val noSplittingWhenLayerdExport: Boolean = false,
    val timelapseRecording: Boolean = false,
    val timelapseScaler: Int = 3,
    val timelapseSpeed: Int = 1,
    val timelapseBgColor: ColorItem = ColorItem(),
    val frameDatas: List<FrameData> = emptyList(),
    val layerDatas: List<LayerData> = emptyList(),
    val paletteData: PaletteData = PaletteData(),
    val referenceImageDatas: List<ReferenceImage> = emptyList(),
    val generatorInfo: GeneratorInfo? = null
)

@Serializable
data class Size(val width: Int, val height: Int)

@Serializable
data class Point(val x: Int = 0, val y: Int = 0)

@Serializable
data class Bounds(val origin: Point = Point(), val size: Size = Size(0, 0))

@Serializable
data class FrameData(
    val id: String,
    val clipName: String = "",
    val clipDir: String = "Forward",
    val duration: Int = 1,
    val isClipHead: Boolean = false
)

@Serializable
data class LayerData(
    val id: String,
    val name: String = "Layer",
    val isLocked: Boolean = false,
    val isGroup: Boolean = false,
    val isGroupOpen: Boolean = true,
    val contentVisible: Boolean = true,
    val isClippingMask: Boolean = false,
    val blendMode: String = "Normal",
    val opacity: Float = 1f,
    val inherit: Boolean = false,
    val cells: List<CellData> = emptyList()
)

@Serializable
data class CellData(
    val id: String,
    val bounds: Bounds = Bounds(),
    val opacity: Float = 1f,
    val inherit: Boolean = false
)

@Serializable
data class PaletteData(
    val id: String = "",
    val colorItemMap: Map<String, ColorItem> = emptyMap(),
    val colorItemTps: List<String> = emptyList()
)

@Serializable
data class ColorItem(
    val id: String = "",
    val h: Float = 0f,
    val s: Float = 0f,
    val b: Float = 1f,
    val a: Float = 1f,
    val ra: Float = 0f,
    val ga: Float = 0f,
    val ba: Float = 0f
)

@Serializable
data class ReferenceImage(
    val id: String,
    val name: String = "",
    val opacity: Float = 1f,
    val visible: Boolean = true,
    @SerialName("transform") val transform: FloatArray = FloatArray(6)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReferenceImage) return false
        return id == other.id && name == other.name &&
            opacity == other.opacity && visible == other.visible &&
            transform.contentEquals(other.transform)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + transform.contentHashCode()
        return result
    }
}

@Serializable
data class GeneratorInfo(
    val deviceModel: String = "android",
    val appVersion: String = "R35",
    val osVersion: String = "",
    val appName: String = "mcpresprite"
)
