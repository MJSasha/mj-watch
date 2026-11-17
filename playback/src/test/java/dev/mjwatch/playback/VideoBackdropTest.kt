package dev.mjwatch.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VideoBackdropTest {
    @Test fun takesDarkenedEdgeColoursAndLeavesBlackFramesBlack() {
        val pixels=IntArray(32) {index->when(index/4) {0,1->0xFFFF0000.toInt();6,7->0xFF0000FF.toInt();else->0xFF00FF00.toInt()}}
        assertArrayEquals(intArrayOf(0xFF3F0000.toInt(),0xFF00003F.toInt()),videoBackdropColors(pixels,4,8))
        assertArrayEquals(intArrayOf(0xFF000000.toInt(),0xFF000000.toInt()),videoBackdropColors(IntArray(32),4,8))
    }
}
