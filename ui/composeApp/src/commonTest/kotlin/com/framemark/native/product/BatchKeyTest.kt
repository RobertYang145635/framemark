package com.framemark.native.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The idempotency key must cover every input that changes the exported pixels
 * (R1). Each case here submits through the real queue, so a key that forgets a
 * field shows up as a wrongly rejected job, not as a style nit.
 */
class BatchKeyTest {
    private fun baseDesign(): PhotoDesign =
        PhotoDesign.base("simple-4").withParam("inset", 0.1).withText("model", "X-T5")

    private fun job(
        id: String,
        design: PhotoDesign = baseDesign(),
        overlay: WatermarkSpec? = watermarkSpec(1),
        photoPath: String? = "/photos/a.jpg",
        photoFingerprint: String? = "fp-a",
        exportSettings: ExportSettings = ExportSettings(sizeMode = ExportSizeMode.LongEdge, longEdge = 6000),
    ): BatchJob = BatchJob.forPhoto(
        id = id,
        photoPath = photoPath,
        photoFingerprint = photoFingerprint,
        displayName = "a.jpg",
        design = design.withOverlay(overlay),
        exportSettings = exportSettings,
        now = 1,
        nameIndex = 1,
        outputDirectory = "/out",
    )

    /** Same declared name/dimensions, caller-chosen payload: the file name is not the identity. */
    private fun namedWatermark(name: String, seed: Int, size: Int = 64): WatermarkSpec {
        val bytes = ByteArray(size) { ((it + seed) and 0xff).toByte() }
        val asset = WatermarkAsset.create(name, "image/png", 800, 200, bytes)
        return WatermarkSpec(asset = asset, anchor = 8, width = 0.3f, opacity = 0.7f)
    }

    private fun added(queue: BatchQueue, job: BatchJob): BatchQueue {
        val outcome = queue.submit(job)
        assertTrue(outcome is SubmitOutcome.Added, "${job.id} 被误判为 Duplicate")
        return outcome.queue
    }

    @Test
    fun every_single_field_difference_queues_a_new_job() {
        val base = job("j-base")
        var queue = added(BatchQueue(), base)

        val variants = listOf(
            "theme" to job("j-theme", design = baseDesign().withTheme("black")),
            "ratio" to job("j-ratio", design = baseDesign().withRatio("4:5")),
            "fontScale" to job("j-font-scale", design = baseDesign().withFontScale(1.5)),
            "logo" to job("j-logo", design = baseDesign().withLogo("leica")),
            "用户 Logo" to job("j-user-logo", design = baseDesign().withLogo("user:ul-1")),
            "水印位置" to job("j-overlay-position", overlay = watermarkSpec(1).copy(dx = 0.12f, dy = -0.08f)),
            "水印透明度" to job("j-overlay-opacity", overlay = watermarkSpec(1).copy(opacity = 0.35f)),
            "水印角度" to job("j-overlay-angle", overlay = watermarkSpec(1).copy(rotation = 45f)),
            "照片比例" to job("j-overlay-aspect", overlay = watermarkSpec(1).copy(photoAspect = 1.2f)),
        )

        val keys = mutableSetOf(base.idempotencyKey)
        variants.forEach { (label, variant) ->
            assertNotEquals(base.idempotencyKey, variant.idempotencyKey, "$label 单字段变化没有改变 key")
            assertTrue(keys.add(variant.idempotencyKey), "$label 与其它变体的 key 撞车")
            queue = added(queue, variant)
        }
        assertEquals(variants.size + 1, queue.jobs.size)
    }

    @Test
    fun a_watermark_replaced_with_the_same_name_but_new_bytes_is_a_new_job() {
        val original = job("j1")
        val queue = added(BatchQueue(), original)
        val name = original.overlay!!.asset.name

        val replaced = job("j2", overlay = namedWatermark(name, seed = 5))
        assertEquals(name, replaced.overlay!!.asset.name, "前置条件：同名")
        assertNotEquals(original.overlay!!.asset.sha256, replaced.overlay!!.asset.sha256)
        assertNotEquals(original.idempotencyKey, replaced.idempotencyKey)
        val withReplaced = added(queue, replaced)

        // Same name and same bytes is still the same job.
        val identical = job("j3", overlay = namedWatermark(name, seed = 1))
        assertTrue(withReplaced.submit(identical) is SubmitOutcome.Duplicate, "同名同内容的签被判成新任务")
    }

    @Test
    fun identical_inputs_are_a_duplicate() {
        val first = job("j1")
        val queue = added(BatchQueue(), first)
        val outcome = queue.submit(job("j2"))
        assertTrue(outcome is SubmitOutcome.Duplicate, "完全相同输入重复入队")
        assertEquals("j1", outcome.existingId)
        assertEquals(1, outcome.queue.jobs.size)
    }

    @Test
    fun keys_survive_a_session_store_round_trip() {
        val fs = FakeFs()
        val store = SessionStore(fs)
        val jobs = listOf(
            job("j-base"),
            job("j-theme", design = baseDesign().withTheme("black")),
            job("j-ratio", design = baseDesign().withRatio("4:5")),
            job("j-font-scale", design = baseDesign().withFontScale(1.5)),
            job("j-logo", design = baseDesign().withLogo("leica")),
            job("j-overlay-position", overlay = watermarkSpec(1).copy(dx = 0.12f, dy = -0.08f)),
            job("j-overlay-opacity", overlay = watermarkSpec(1).copy(opacity = 0.35f)),
        )
        var queue = BatchQueue()
        jobs.forEach { queue = added(queue, it) }
        assertEquals(jobs.size, queue.jobs.map { it.idempotencyKey }.toSet().size)

        assertTrue(store.saveBatch(queue) is StoreResult.Ok)
        val loaded = store.loadBatch().ok()

        assertEquals(queue.jobs.map { it.id }, loaded.jobs.map { it.id })
        queue.jobs.zip(loaded.jobs).forEach { (before, after) ->
            assertEquals(before.idempotencyKey, after.idempotencyKey, "${before.id} 重载后 idempotencyKey 变了")
            assertEquals(before.designKey, after.designKey, "${before.id} 重载后 designKey 变了")
            assertEquals(before.exportKey, after.exportKey, "${before.id} 重载后 exportKey 变了")
        }

        // A job rebuilt from the reloaded snapshot still dedupes against its original.
        val again = loaded.submit(jobs.first().copy(id = "j-rebuilt"))
        assertTrue(again is SubmitOutcome.Duplicate, "重载后的快照无法识别同一个任务")
        assertEquals("j-base", again.existingId)
    }

    @Test
    fun photo_identity_still_prefers_the_fingerprint_over_the_path() {
        val queue = added(BatchQueue(), job("j1", photoPath = "/photos/a.jpg", photoFingerprint = "fp-a"))

        val moved = queue.submit(job("j2", photoPath = "/elsewhere/a.jpg", photoFingerprint = "fp-a"))
        assertTrue(moved is SubmitOutcome.Duplicate, "同一指纹换了路径被当成新照片")

        val differentPhoto = queue.submit(job("j3", photoPath = "/photos/a.jpg", photoFingerprint = "fp-other"))
        assertTrue(differentPhoto is SubmitOutcome.Added, "不同照片共用路径被误判重复")
    }

    @Test
    fun design_key_covers_every_design_field() {
        val base = baseDesign()
        val variants = listOf(
            base.withTheme("black"),
            base.withRatio("4:5"),
            base.withFontScale(1.5),
            base.withLogo("leica"),
            base.withParam("inset", 0.2),
            base.withText("model", "X-H2"),
            PhotoDesign.base("glass-white-1"),
        )
        val keys = variants.map { BatchKeys.designKey(it) }
        assertEquals(variants.size, keys.toSet().size, "存在两组不同设计共用同一个 designKey")
        assertTrue(BatchKeys.designKey(base) !in keys, "变体与基准 designKey 相同")
    }
}
