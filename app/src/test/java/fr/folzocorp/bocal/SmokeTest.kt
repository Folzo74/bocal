package fr.folzocorp.bocal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SmokeTest {

    @Test
    fun mainActivityEstPresentDansLeModule() {
        val clazz = Class.forName("fr.folzocorp.bocal.MainActivity")
        assertEquals(MainActivity::class.java, clazz)
        assertNotNull(clazz)
    }
}
