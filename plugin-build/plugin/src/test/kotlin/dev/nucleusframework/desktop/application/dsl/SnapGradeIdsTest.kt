package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapGradeIdsTest {
    @Test
    fun `snap grades expose snapcraft ids`() {
        assertEquals("stable", SnapGrade.Stable.id)
        assertEquals("devel", SnapGrade.Devel.id)
    }
}
