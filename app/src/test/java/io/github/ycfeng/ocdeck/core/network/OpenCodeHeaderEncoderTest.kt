package io.github.ycfeng.ocdeck.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeHeaderEncoderTest {
    @Test
    fun encodesDirectoryLikeEncodeURIComponent() {
        assertEquals(
            "E%3A%2Fproject%2Fopencode%2Ftest",
            OpenCodeHeaderEncoder.encodeDirectory("E:/project/opencode/test"),
        )
    }

    @Test
    fun encodesSpacesAsPercentTwenty() {
        assertEquals(
            "E%3A%2Fproject%20with%20space%2Ftest",
            OpenCodeHeaderEncoder.encodeDirectory("E:/project with space/test"),
        )
    }

    @Test
    fun encodesUtf8Characters() {
        assertEquals(
            "E%3A%2Fproject%2F%E6%B5%8B%E8%AF%95",
            OpenCodeHeaderEncoder.encodeDirectory("E:/project/测试"),
        )
    }
}
