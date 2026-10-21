package com.tmplayer.data

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONException
import com.tmplayer.json.JSONObject
import com.tmplayer.online.hostOf
import com.tmplayer.platform.formEncode
import com.tmplayer.platform.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JSON, hashing and URL pieces the online metadata reads with, on Node and the JVM. */
class PureWebKitTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun theJsonLayerReadsLikeOrgJson() {
        val o = JSONObject("""{"name":"Harbour","year":"2019","count":12,"rating":7.5,"ok":true,"none":null,"list":[{"a":1},"x"]}""")
        assertEquals("Harbour", o.optString("name"))
        assertEquals("", o.optString("missing"))
        assertEquals("", o.optString("none"))
        assertTrue(o.isNull("none"))
        assertTrue(o.isNull("missing"))
        assertEquals(2019, o.optInt("year"))
        assertEquals(12L, o.optLong("count"))
        assertEquals(7.5, o.optDouble("rating"))
        assertTrue(o.optDouble("missing").isNaN())
        assertTrue(o.optBoolean("ok"))
        assertEquals("12", o.opt("count").toString())
        val list = o.optJSONArray("list")!!
        assertEquals(2, list.length())
        assertEquals(1, list.optJSONObject(0)!!.optInt("a"))
        assertNull(list.optJSONObject(1))
        assertFailsWith<JSONException> { o.getString("missing") }
        assertFailsWith<JSONException> { JSONObject("not json") }
        val built = JSONObject().put("t", "a\"b").put("n", 3).put("l", JSONArray().put(1).put("z"))
        assertEquals(JSONObject(built.toString()), built)
        assertFalse(built.has("gone"))
    }

    @Test
    fun formEncodingAndHosts() {
        assertEquals("Harbour+Notes+%C3%A9t%C3%A9%26x%3D1", formEncode("Harbour Notes été&x=1"))
        assertEquals("image.tmdb.org", hostOf("https://image.tmdb.org/t/p/w342/a.jpg"))
        assertEquals("static.tvmaze.com", hostOf("https://user@static.tvmaze.com:443/x"))
        assertNull(hostOf("not a url"))
    }
}
