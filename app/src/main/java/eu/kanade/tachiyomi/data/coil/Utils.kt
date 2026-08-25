package eu.kanade.tachiyomi.data.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse

internal inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else width.toPx(scale)
}

internal inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else height.toPx(scale)
}

internal fun Dimension.toPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}

fun ImageRequest.Builder.cropBorders(enable: Boolean) = apply {
    extras[cropBordersKey] = enable
}

val Options.cropBorders: Boolean
    get() = getExtra(cropBordersKey)

private val cropBordersKey = Extras.Key(default = false)

fun ImageRequest.Builder.customDecoder(enable: Boolean) = apply {
    extras[customDecoderKey] = enable
}

val Options.customDecoder: Boolean
    get() = getExtra(customDecoderKey)

private val customDecoderKey = Extras.Key(default = false)

fun ImageRequest.Builder.newDecoder(enable: Boolean) = apply {
    extras[newDecoderKey] = enable
}
val Options.newDecoder: Boolean
    get() = getExtra(newDecoderKey)

private val newDecoderKey = Extras.Key(default = false)

fun ImageRequest.Builder.enhanced(enable: Boolean) = apply {
    extras[enhancedKey] = enable
}

fun Options.isEnhanced(): Boolean = getExtra(enhancedKey)

private val enhancedKey = Extras.Key(default = false)

fun ImageRequest.Builder.mangaId(id: Long) = apply {
    extras[mangaIdKey] = id
}

fun Options.mangaIdOrNull(): Long? = getExtra(mangaIdKey).takeIf { it != -1L }

private val mangaIdKey = Extras.Key(default = -1L)

fun ImageRequest.Builder.chapterId(id: Long) = apply {
    extras[chapterIdKey] = id
}

fun Options.chapterIdOrNull(): Long? = getExtra(chapterIdKey).takeIf { it != -1L }

private val chapterIdKey = Extras.Key(default = -1L)

fun ImageRequest.Builder.pageIndex(index: Int) = apply {
    extras[pageIndexKey] = index
}

fun Options.pageIndexOrNull(): Int? = getExtra(pageIndexKey).takeIf { it != -1 }

private val pageIndexKey = Extras.Key(default = -1)

fun ImageRequest.Builder.pageVariant(variant: String) = apply {
    extras[pageVariantKey] = variant
}

fun Options.pageVariantOrNull(): String? = getExtra(pageVariantKey).takeIf { it.isNotEmpty() }

private val pageVariantKey = Extras.Key(default = "")
