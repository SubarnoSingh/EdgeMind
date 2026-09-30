package com.example.EdgeMemo.presentation.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeNavigatorPhase3Test {

    @Test
    fun recordDetailStacksAboveAssetAndPopsDeterministically() {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.MACHINES)
        nav.openMachine("p101")
        nav.openRecord("m-1")
        assertEquals(EdgeRoute.RecordDetail("m-1"), nav.current)
        assertEquals(EdgeTab.MACHINES, nav.currentTab)

        assertTrue(nav.pop())
        assertEquals(EdgeRoute.MachineDetail("p101"), nav.current)
        assertTrue(nav.pop())
        assertEquals(EdgeRoute.Machines, nav.current)
        assertFalse(nav.pop())
    }

    @Test
    fun recordDetailDedupesIdenticalId() {
        val nav = EdgeNavigator()
        nav.openMachine("p101")
        nav.openRecord("same")
        nav.openRecord("same")
        assertTrue(nav.pop())
        assertEquals(EdgeRoute.MachineDetail("p101"), nav.current)
    }
}
