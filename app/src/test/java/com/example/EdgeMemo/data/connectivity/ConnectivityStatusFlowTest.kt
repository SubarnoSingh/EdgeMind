package com.example.EdgeMemo.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Connectivity status used by the header chip. Real ConnectivityManager
 * under Robolectric; the pure status decision is pinned directly, and
 * callback transitions are exercised through the shadow implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectivityStatusFlowTest {

    private lateinit var context: Context
    private lateinit var manager: ConnectivityManager
    private var status: ConnectivityStatusFlow? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @After
    fun tearDown() {
        status?.close()
    }

    @Test
    fun pureDecisionIsOnlineOnlyWithInternetCapability() {
        val network = ShadowNetwork.newInstance(100)
        assertFalse("no network is offline", isOnlineStatus(null, null))
        assertFalse("network without capabilities is offline", isOnlineStatus(network, null))
        val noInternet = ShadowNetworkCapabilities.newInstance()
        assertFalse("no INTERNET capability is offline", isOnlineStatus(network, noInternet))
        val withInternet = shadowOf(ShadowNetworkCapabilities.newInstance())
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertTrue("INTERNET capability is online", isOnlineStatus(network, withInternet))
    }

    @Test
    fun initialStatusIsOfflineWhenNoNetworkExists() {
        status = ConnectivityStatusFlow(context)
        assertFalse("fresh device with no network must start OFFLINE", status!!.isOnline.value)
    }

    @Test
    fun statusFollowsNetworkTransitions() {
        status = ConnectivityStatusFlow(context)
        assertFalse(status!!.isOnline.value)

        val defaultNetwork = manager.activeNetwork!!
        val internetCaps = shadowOf(ShadowNetworkCapabilities.newInstance())
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(defaultNetwork, internetCaps)
        shadowOf(manager).setDefaultNetworkActive(true)
        assertTrue("network appearing with INTERNET flips to online", status!!.isOnline.value)

        shadowOf(manager).setNetworkCapabilities(defaultNetwork, ShadowNetworkCapabilities.newInstance())
        shadowOf(manager).setDefaultNetworkActive(true)
        assertFalse("losing INTERNET capability flips to offline", status!!.isOnline.value)
    }

    @Test
    fun closeDoesNotThrowAndRepeatedCloseIsSafe() {
        status = ConnectivityStatusFlow(context)
        status!!.close()
        status!!.close()
    }
}
