package com.framemark.native.product

import com.framemark.native.library.LibraryCard
import com.framemark.native.workflow.DesignSnapshot
import com.framemark.native.workflow.EngineFailure
import com.framemark.native.product.ExportFormat
import com.framemark.native.product.ExportSettings
import com.framemark.native.workflow.NativeEngine
import com.framemark.native.workflow.NativeImage
import com.framemark.native.workflow.NativeRenderApi
import com.framemark.native.workflow.OverlaySpec
import com.framemark.native.workflow.SampleFormat
import com.framemark.native.workflow.WorkflowEnv
import com.framemark.native.workflow.WorkflowPlatform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The export entry must fail with a structured error instead of a placeholder. */
class ExportFailureTest {
    private class FakePlatform : WorkflowPlatform {
        override val dataDir = "/tmp/fm-data"
        override val exportDir = "/tmp/fm-exports"
        override fun readTextFile(path: String): String? = null
        override fun writeTextFile(path: String, text: String) = Unit
        override fun writeBytes(path: String, bytes: ByteArray): Boolean = true
        override fun exists(path: String): Boolean = false
        override fun fileSize(path: String): Long = 0L
        override fun readBytes(path: String): ByteArray? = null
        override fun encodeSampleImage(width: Int, height: Int, format: SampleFormat, quality: Int): ByteArray? = null
        override fun dateStamp(): String = "20261030"
        override fun timeStamp(): String = "120000"
        override fun formatDateTime(epochMillis: Long): String = ""
        override fun nowMillis(): Long = 0L
    }

    private fun card(id: String = "card-1") = LibraryCard(
        id = id,
        name = "卡片 01",
        category = "card",
        categoryName = "卡片边框",
        brand = null,
        vendor = null,
        family = null,
        fidelity = null,
        badge = null,
        en = null,
        systemStyle = null,
        previewSourcePath = "",
        previewFile = "",
    )

    private fun stubRender(supports: Boolean, export: Boolean = supports, webp: Boolean = false) =
        object : NativeRenderApi {
            override fun supports(cardId: String): Boolean = supports
            override fun supportsExport(cardId: String): Boolean = export
            override fun supportsWebpExport(): Boolean = webp
            override suspend fun render(
                cardId: String,
                design: DesignSnapshot,
                mode: String,
                maxEdge: Int?,
                format: SampleFormat?,
                quality: Int?,
                longEdge: Int?,
                outputPath: String?,
                webpLossless: Boolean,
                overlay: OverlaySpec?,
                photoPath: String?,
                interactive: Boolean,
            ): NativeImage = error("render must not be reached")
        }

    private fun <T> withEnv(render: NativeRenderApi?, block: () -> T): T {
        val oldPlatform = WorkflowEnv.platform
        val oldRender = WorkflowEnv.nativeRender
        WorkflowEnv.platform = FakePlatform()
        WorkflowEnv.nativeRender = render
        return try {
            block()
        } finally {
            WorkflowEnv.platform = oldPlatform
            WorkflowEnv.nativeRender = oldRender
        }
    }

    private val design = DesignSnapshot("card-1", null, emptyMap(), emptyMap())

    @Test
    fun a_missing_engine_is_a_structured_failure_not_a_placeholder() {
        val error = withEnv(null) {
            assertFailsWith<EngineFailure> {
                runBlocking { NativeEngine.export(card(), ExportSettings(), design) }
            }
        }
        assertEquals("FM-E-ENGINE-001", error.code)
        assertTrue(error.userMessage.contains("无法导出"))
    }

    @Test
    fun an_unwired_card_is_rejected_without_a_placeholder() {
        val error = withEnv(stubRender(supports = false)) {
            assertFailsWith<EngineFailure> {
                runBlocking { NativeEngine.export(card(), ExportSettings(), design) }
            }
        }
        assertEquals("FM-E-ENGINE-002", error.code)
    }

    /**
     * UI copy audit 2026-11-13, leak 5: the export wrapper used to copy the throwable
     * text straight into `EngineFailure.userMessage` — the field the export dialog and
     * the phone notice print. An engine-side exception carries English diagnostics and
     * can name the runtime binary (`IR 引擎缺失（bin/e2b.exe）`), so the sentence on
     * screen could be a developer message. The user message is now fixed copy; the raw
     * text stays in the cause and in the log.
     */
    @Test
    fun a_raw_engine_exception_never_becomes_the_user_message() {
        val error = withEnv(stubRender(supports = true)) {
            assertFailsWith<EngineFailure> {
                runBlocking { NativeEngine.export(card(), ExportSettings(), design) }
            }
        }
        assertEquals("FM-E-ENGINE-002", error.code)
        assertFalse(
            error.userMessage.contains("render must not be reached"),
            "the stub's own English text reached the user message: ${error.userMessage}",
        )
        assertTrue(
            error.userMessage.any { it in '\u4e00'..'\u9fff' },
            "the user message must be the Chinese sentence: ${error.userMessage}",
        )
        assertEquals(
            error.cause?.message,
            "render must not be reached",
            "the raw text must stay reachable through the cause",
        )
    }

    @Test
    fun webp_without_an_encoder_is_rejected() {
        val error = withEnv(stubRender(supports = true, webp = false)) {
            assertFailsWith<EngineFailure> {
                runBlocking {
                    NativeEngine.export(
                        card(),
                        ExportSettings(format = ExportFormat.WebP),
                        design,
                    )
                }
            }
        }
        assertEquals("FM-E-EXPORT-001", error.code)
    }

    @Test
    fun the_batch_path_uses_the_same_structured_failure() {
        val error = withEnv(null) {
            assertFailsWith<EngineFailure> {
                runBlocking {
                    NativeEngine.exportCard(
                        cardId = "card-1",
                        cardName = "卡片 01",
                        styleId = null,
                        settings = ExportSettings(),
                        design = design,
                    )
                }
            }
        }
        assertEquals("FM-E-ENGINE-001", error.code)
    }
}
