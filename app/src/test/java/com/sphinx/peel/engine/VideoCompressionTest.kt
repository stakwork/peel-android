package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionTest {
    @Test fun limitIsOneHundredMegabytes() {
        assertEquals(100_000_000L, VideoCompression.SEND_LIMIT)
        assertEquals(100_000_000L, StagedAttachments.sendLimit(agent = false))
        assertTrue(VideoCompression.COMPRESSION_TARGET_BYTES < VideoCompression.SEND_LIMIT)
    }

    @Test fun onlyOversizedNeedsCompression() {
        assertTrue(!VideoCompression.needsCompression(100_000_000L))
        assertTrue(VideoCompression.needsCompression(100_000_001L))
    }

    @Test fun bitrateFitsTheTargetAfterAudio() {
        val target = 95L * 1024 * 1024
        assertEquals(target * 8 / 100 - 128_000, VideoCompression.videoBitrate(100_000))
    }

    @Test fun tiersChangeAtTheirBoundaries() {
        assertEquals(1920, VideoCompression.tier(6_500_000).longSide)
        assertEquals(1280, VideoCompression.tier(6_499_999).longSide)
        assertEquals(1280, VideoCompression.tier(2_500_000).longSide)
        assertEquals(854, VideoCompression.tier(2_499_999).longSide)
        assertEquals(854, VideoCompression.tier(750_000).longSide)
        assertEquals(640, VideoCompression.tier(749_999).longSide)
        assertEquals(360, VideoCompression.tier(0).shortSide)
    }

    @Test fun outputKeepsAspectAndIsEven() {
        assertEquals(1280 to 720, VideoCompression.outputSize(3840, 2160, 0, VideoCompression.tier(3_000_000)))
        // A phone's portrait clip stored sideways: rotation 90 makes it upright 1080x1920 before the cap.
        assertEquals(720 to 1280, VideoCompression.outputSize(1920, 1080, 90, VideoCompression.tier(3_000_000)))
        // Never scaled up.
        assertEquals(640 to 360, VideoCompression.outputSize(640, 360, 0, VideoCompression.tier(10_000_000)))
        val (w, h) = VideoCompression.outputSize(1001, 563, 0, VideoCompression.tier(1_000_000))
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun tooLongFailsUnderTheFloor() {
        // 95 MiB over ~2 hours is well under 500 kbps.
        assertNull(VideoCompression.plan(7_200_000, 1920, 1080, 0))
        val plan = VideoCompression.plan(60_000, 1920, 1080, 0)
        assertNotNull(plan)
        assertTrue(plan!!.videoBitrate >= VideoCompression.FLOOR_BITRATE)
        assertEquals(1920, plan.width)
    }

    @Test fun transferFractionIsClamped() {
        assertEquals(0f, TransferTracker.fraction(5, 0), 0f)
        assertEquals(0.5f, TransferTracker.fraction(5, 10), 0f)
        assertEquals(1f, TransferTracker.fraction(20, 10), 0f)
    }

    @Test fun dismissOnlyClearsFinishedStates() {
        val tracker = TransferTracker()
        tracker.uploading(0.3f)
        tracker.dismiss()
        assertEquals(TransferState.Uploading(0.3f), tracker.state.value)
        tracker.failed("no")
        tracker.dismiss()
        assertEquals(TransferState.Idle, tracker.state.value)
    }
}
