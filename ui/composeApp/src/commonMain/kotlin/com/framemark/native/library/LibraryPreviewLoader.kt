package com.framemark.native.library

import androidx.compose.ui.graphics.ImageBitmap
import com.framemark.native.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The one code path that turns a [LibraryCard] into a bitmap. The UI tile uses it
 * per visible card, and the desktop `LibraryVerify` entry point uses it for all
 * 175 cards so evidence and runtime cannot drift apart.
 */
object LibraryPreviewLoader {
    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadBytes(card: LibraryCard): ByteArray {
        require(card.previewFile.isNotBlank()) { "no preview row for ${card.id}" }
        return Res.readBytes(LibraryDataSource.PREVIEW_DIR + card.previewFile)
    }

    suspend fun decode(bytes: ByteArray): ImageBitmap = bytes.decodeToImageBitmap()
}
