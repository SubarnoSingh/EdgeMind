package com.example.EdgeMemo.presentation.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeNavigatorTest {

    @Test
    fun startsOnDashboard() {
        val nav = EdgeNavigator()
        assertEquals(EdgeRoute.Dashboard, nav.current)
        assertEquals(EdgeTab.DASHBOARD, nav.currentTab)
        assertEquals(EdgeRoute.Dashboard, nav.route.value)
    }

    @Test
    fun tabSwitchCollapsesPushStack() {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.MACHINES)
        nav.openMachine("p101")
        nav.openRecords()
        assertEquals(EdgeRoute.Records, nav.current)

        nav.select(EdgeTab.ASK)
        assertEquals(EdgeRoute.Ask, nav.current)
        assertFalse("the push stack was collapsed", nav.pop())
    }

    @Test
    fun popReturnsFalseAtRootTab() {
        val nav = EdgeNavigator()
        assertFalse(nav.pop())
        nav.select(EdgeTab.SYNC)
        assertFalse("tab roots never pop past the tab", nav.pop())
    }

    @Test
    fun pushDeduplicatesIdenticalRoutes() {
        val nav = EdgeNavigator()
        nav.push(EdgeRoute.Records)
        nav.push(EdgeRoute.Records)
        assertTrue(nav.pop())
        assertFalse("only one Records instance was stacked", nav.pop())
    }

    @Test
    fun machineDetailBelongsToMachinesTab() {
        val nav = EdgeNavigator()
        nav.select(EdgeTab.MACHINES)
        nav.openMachine("p-101")
        assertEquals(EdgeRoute.MachineDetail("p-101"), nav.current)
        assertEquals(EdgeTab.MACHINES, nav.currentTab)
    }

    @Test
    fun recordsRouteHasNoTabHighlight() {
        val nav = EdgeNavigator()
        nav.openRecords()
        assertNull(nav.currentTab)
    }

    @Test
    fun routeFlowTracksNavigation() {
        val nav = EdgeNavigator()
        val seen = mutableListOf(nav.route.value)
        nav.select(EdgeTab.SETTINGS)
        seen += nav.route.value
        nav.openRecords()
        seen += nav.route.value
        nav.pop()
        seen += nav.route.value
        assertEquals(
            listOf(EdgeRoute.Dashboard, EdgeRoute.Settings, EdgeRoute.Records, EdgeRoute.Settings),
            seen,
        )
    }
}
