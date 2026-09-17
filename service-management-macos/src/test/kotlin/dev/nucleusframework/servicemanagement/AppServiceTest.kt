package dev.nucleusframework.servicemanagement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppServiceTest {
    @Test
    fun `service kinds keep type identifiers and add plist suffixes`() {
        assertEquals(AppService.TYPE_MAIN_APP, AppService.MainApp.type)
        assertEquals("", AppService.MainApp.identifier)

        val login = AppService.LoginItem("com.example.helper")
        assertEquals(AppService.TYPE_LOGIN_ITEM, login.type)
        assertEquals("com.example.helper", login.identifier)

        val agent = AppService.Agent("com.example.agent")
        assertEquals(AppService.TYPE_AGENT, agent.type)
        assertEquals("com.example.agent.plist", agent.identifier)
        assertEquals("com.example.agent.plist", AppService.Agent("com.example.agent.plist").identifier)

        val daemon = AppService.Daemon("com.example.daemon")
        assertEquals(AppService.TYPE_DAEMON, daemon.type)
        assertEquals("com.example.daemon.plist", daemon.identifier)
        assertEquals("already.plist", AppService.Daemon("already.plist").identifier)
    }

    @Test
    fun `status maps raw values and falls back to not found`() {
        assertEquals(AppServiceStatus.NOT_REGISTERED, AppServiceStatus.fromRawValue(0))
        assertEquals(AppServiceStatus.ENABLED, AppServiceStatus.fromRawValue(1))
        assertEquals(AppServiceStatus.REQUIRES_APPROVAL, AppServiceStatus.fromRawValue(2))
        assertEquals(AppServiceStatus.NOT_FOUND, AppServiceStatus.fromRawValue(3))
        assertEquals(AppServiceStatus.NOT_FOUND, AppServiceStatus.fromRawValue(99))
        assertEquals(0, AppServiceStatus.NOT_REGISTERED.rawValue)
        assertEquals(1, AppServiceStatus.ENABLED.rawValue)
    }

    @Test
    fun `exception carries the native message`() {
        val error = AppServiceException("plist missing")
        assertEquals("plist missing", error.message)
        assertIs<Exception>(error)
    }

    @Test
    fun `manager no-op or read-only status depending on availability`() {
        val available = AppServiceManager.isAvailable
        if (!available) {
            val result = AppServiceManager.register(AppService.MainApp)
            assertTrue(result.isFailure)
            assertIs<UnsupportedOperationException>(result.exceptionOrNull())
            var unregisterError: String? = null
            AppServiceManager.unregister(AppService.Agent("com.example.agent")) { unregisterError = it }
            assertEquals("SMAppService not available", unregisterError)
            assertEquals(AppServiceStatus.NOT_REGISTERED, AppServiceManager.status(AppService.MainApp))
            assertFalse(AppServiceManager.openSystemSettingsLoginItems())
        } else {
            val status = AppServiceManager.status(AppService.MainApp)
            assertTrue(status in AppServiceStatus.entries)
            val helper = AppServiceManager.status(AppService.LoginItem("com.example.missing.helper"))
            assertTrue(helper in AppServiceStatus.entries)
            val agent = AppServiceManager.status(AppService.Agent("com.example.missing.agent"))
            assertTrue(agent in AppServiceStatus.entries)
            val daemon = AppServiceManager.status(AppService.Daemon("com.example.missing.daemon"))
            assertTrue(daemon in AppServiceStatus.entries)
        }
    }
}
