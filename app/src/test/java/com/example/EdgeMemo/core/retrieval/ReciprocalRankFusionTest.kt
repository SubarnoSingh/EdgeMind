package com.example.EdgeMemo.core.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReciprocalRankFusionTest {

    @Test
    fun itemPresentInBothListsScoresAboveEitherAlone() {
        val fused = ReciprocalRankFusion.fuse(
            denseRanking = listOf("a", "b"),
            keywordRanking = listOf("b", "c"),
        )
        val byId = fused.toMap()
        assertTrue("b should outrank a", byId.getValue("b") > byId.getValue("a"))
        assertTrue("b should outrank c", byId.getValue("b") > byId.getValue("c"))
    }

    @Test
    fun higherRankContributesMore() {
        val fused = ReciprocalRankFusion.fuse(
            denseRanking = listOf("first", "second", "third"),
            keywordRanking = emptyList(),
        )
        assertTrue(fused[0].first == "first")
        assertTrue(fused[0].second > fused[1].second)
        assertTrue(fused[1].second > fused[2].second)
    }

    @Test
    fun keywordOnlyResultTiesForTopWithRankOneDense() {
        val fused = ReciprocalRankFusion.fuse(
            denseRanking = (1..20).map { "d$it" },
            keywordRanking = listOf("exact-id"),
        )
        // a rank-1 keyword hit scores identically to a rank-1 dense hit; the
        // deterministic tie-break (ascending id) places it directly after "d1"
        val topScore = fused.first().second
        assertEquals(topScore, fused[1].second, 0.0)
        assertEquals("exact-id", fused[1].first)
    }

    @Test
    fun fuseIsDeterministicAndTiesBreakById() {
        val a = ReciprocalRankFusion.fuse(listOf("x", "y"), listOf("y", "x"))
        val b = ReciprocalRankFusion.fuse(listOf("x", "y"), listOf("y", "x"))
        assertEquals(a, b)
        // an item ranking 1st in dense and 1st in keyword ties with itself; single entry
        val single = ReciprocalRankFusion.fuse(listOf("z"), listOf("z"))
        assertEquals(listOf("z"), single.map { it.first })
    }
}