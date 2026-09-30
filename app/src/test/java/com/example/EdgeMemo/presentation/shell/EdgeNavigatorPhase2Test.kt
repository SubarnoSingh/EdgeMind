package com.example.EdgeMemo.presentation.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeNavigatorPhase2Test {

    @Test
    fun openAskCarriesAssetContextOntoTheAskRoot() {
        val nav = EdgeNavigator()
        nav.openAsk("P101")
        assertEquals(EdgeRoute.Ask, nav.current)
        assertEquals(EdgeTab.ASK, nav.currentTab)
        assertEquals("p101", nav.askAsset.value)
    }

    @Test
    fun plainAskTabSelectionClearsAssetContext() {
        val nav = EdgeNavigator()
        nav.openAsk("p101")
        nav.select(EdgeTab.ASK)
        assertNull(nav.askAsset.value)
        assertEquals(EdgeRoute.Ask, nav.current)
    }

    @Test
    fun clearAskAssetKeepsTheAskRoute() {
        val nav = EdgeNavigator()
        nav.openAsk("line-b")
        nav.clearAskAsset()
        assertNull(nav.askAsset.value)
        assertEquals(EdgeRoute.Ask, nav.current)
    }

    @Test
    fun citationRouteStacksAboveAskAndPopsDeterministically() {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.ASK)
        nav.openCitation(2)
        assertEquals(EdgeRoute.CitationDetail(2), nav.current)
        assertEquals(EdgeTab.ASK, nav.currentTab)

        assertTrue(nav.pop())
        assertEquals(EdgeRoute.Ask, nav.current)
        assertTrue(!nav.pop())
    }

    @Test
    fun citationRouteDedupesIdenticalIndex() {
        val nav = EdgeNavigator()
        nav.openCitation(1)
        nav.openCitation(1)
        assertTrue(nav.pop())
        assertTrue(!nav.pop())
    }

    @Test
    fun machineDetailCanOpenAssetAskThenCitationStack() {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.MACHINES)
        nav.openMachine("p101")
        assertEquals(EdgeRoute.MachineDetail("p101"), nav.current)

        nav.openAsk("p101") // "Ask about this asset" — Ask root with context
        assertEquals(EdgeRoute.Ask, nav.current)
        assertEquals("p101", nav.askAsset.value)
    }
}
