package dev.nucleusframework.desktop.application.dsl

import org.junit.Assert.assertEquals
import org.junit.Test

class AppImageCategoryIdsTest {
    @Test
    fun `app image categories expose desktop entry ids`() {
        assertEquals("AudioVideo", AppImageCategory.AudioVideo.id)
        assertEquals("Development", AppImageCategory.Development.id)
        assertEquals("Utility", AppImageCategory.Utility.id)
    }
}
