package com.fsaint.androidagent.capabilities.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoRecorderSessionTest {
    @Test
    fun recorderConfigurationUsesBoundedMp4H264AacProfile() {
        val configuration = VideoRecorderConfiguration.create(
            width = 1280,
            height = 720,
            maxDurationMs = 700_000,
            maxBytes = 2_000_000,
        )

        assertEquals(VideoRecorderConfiguration.MP4, configuration.outputFormat)
        assertEquals(VideoRecorderConfiguration.H264, configuration.videoEncoder)
        assertEquals(VideoRecorderConfiguration.AAC, configuration.audioEncoder)
        assertEquals(1280, configuration.width)
        assertEquals(720, configuration.height)
        assertEquals(600_000, configuration.maxDurationMs)
        assertEquals(2_000_000, configuration.maxBytes)
    }
}
