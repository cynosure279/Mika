package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ListenerReconnect.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerReconnectTest {
    private fun next(
        watching: Boolean = true, locked: Boolean = false, connected: Boolean = false, authorized: Boolean = true, sdk: Int = 36,
    ) = ListenerReconnect.next(watching, locked, connected, authorized, sdk)

    // 已授权、该连着、请过重绑还是没连上:让系统重新登记一次
    @Test fun authorizedButStillNotConnectedReregisters() = assertEquals(Step.Reregister, next())

    @Test fun connectedIsLeftAlone() = assertEquals(Step.None, next(connected = true))

    // 等的这一会儿里锁屏了:锁着本来就不该连着
    @Test fun lockedIsLeftAlone() = assertEquals(Step.None, next(locked = true))

    // 功能关了、或完全退出了
    @Test fun notWatchingIsLeftAlone() = assertEquals(Step.None, next(watching = false))

    // 通知使用权没了:该由用户去授权,不是重新登记能解决的
    @Test fun unauthorizedIsLeftAlone() = assertEquals(Step.None, next(authorized = false))

    @Test fun onlyWhereTheSystemCanUnbindByName() {
        assertEquals(Step.None, next(sdk = 33))
        assertEquals(Step.Reregister, next(sdk = 34))
    }

    @Test fun theVersionMatchesThePlatformConstant() =
        assertEquals(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE, ListenerReconnect.SDK_BY_NAME)
}
