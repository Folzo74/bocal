package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun litUnChampPlat() {
        val json = """{"login":"https://cloud/x","token":"poll-token"}"""
        assertEquals("poll-token", MiniJson.stringField(json, "token"))
        assertEquals("https://cloud/x", MiniJson.stringField(json, "login"))
        assertNull(MiniJson.stringField(json, "absent"))
    }

    @Test
    fun litUnChampImbriqueOcs() {
        val json = """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},
             "data":{"result":"success","loginName":"demo","token":"3j4k5-abc-6789"}}}
        """.trimIndent()
        assertEquals("3j4k5-abc-6789", MiniJson.stringField(json, "token"))
        assertEquals("demo", MiniJson.stringField(json, "loginName"))
        assertEquals(200, MiniJson.intField(json, "statuscode"))
    }

    @Test
    fun traverseLesTableauxEtLesChainesAAccolades() {
        val json = """{"liste":["{pas}","une}vraie","chaine"],"token":"ok"}"""
        assertEquals("ok", MiniJson.stringField(json, "token"))
    }

    @Test
    fun geLesEchappements() {
        val json = """{"token":"a\"b\\c\/d\ne\tf"}"""
        assertEquals("a\"b\\c/d\ne\tf", MiniJson.stringField(json, "token"))
        val unicode = "{\"token\":\"caf\\u00e9\"}"
        assertEquals("café", MiniJson.stringField(unicode, "token"))
    }

    @Test
    fun repondNullSurJsonInvalide() {
        assertNull(MiniJson.stringField("""{"token":""", "token"))
        assertNull(MiniJson.stringField("", "token"))
        assertTrue(MiniJson.stringField("""{"other":"x"}""", "token") == null)
    }
}
