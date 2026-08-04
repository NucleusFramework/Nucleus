package dev.nucleusframework.notification.windows

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastXmlBuilderTest {
    private fun xmlOf(
        scenario: ToastScenario = ToastScenario.DEFAULT,
        duration: ToastDuration = ToastDuration.DEFAULT,
    ): String =
        ToastXmlBuilder.buildXml(
            toast {
                this.scenario = scenario
                this.duration = duration
                visual { text("Title") }
            },
        )

    @Test
    fun `omits scenario and duration attributes by default`() {
        val xml = xmlOf()
        assertFalse(xml.contains("scenario="), xml)
        assertFalse(xml.contains("duration="), xml)
    }

    @Test
    fun `emits urgent scenario`() {
        assertTrue(xmlOf(scenario = ToastScenario.URGENT).contains("scenario=\"urgent\""))
    }

    @Test
    fun `emits short and long duration`() {
        assertTrue(xmlOf(duration = ToastDuration.SHORT).contains("duration=\"short\""))
        assertTrue(xmlOf(duration = ToastDuration.LONG).contains("duration=\"long\""))
    }
}
