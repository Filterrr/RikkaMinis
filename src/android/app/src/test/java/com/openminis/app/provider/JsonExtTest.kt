package com.openminis.app.provider

import org.json.JSONObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class JsonExtTest {

    @Test
    fun `safeOptString returns fallback when key is missing`() {
        val json = JSONObject()
        assertEquals("default", json.safeOptString("missingKey", "default"))
    }

    @Test
    fun `safeOptString returns fallback when value is null`() {
        val json = JSONObject().put("key", JSONObject.NULL)
        assertEquals("fallback", json.safeOptString("key", "fallback"))
    }

    @Test
    fun `safeOptString returns fallback default empty string when key is missing`() {
        val json = JSONObject()
        assertEquals("", json.safeOptString("missingKey"))
    }

    @Test
    fun `safeOptString returns fallback default empty string when value is null`() {
        val json = JSONObject().put("key", JSONObject.NULL)
        assertEquals("", json.safeOptString("key"))
    }

    @Test
    fun `safeOptString returns actual string value`() {
        val json = JSONObject().put("key", "value")
        assertEquals("value", json.safeOptString("key"))
    }

    @Test
    fun `safeOptString returns actual string value with fallback ignored`() {
        val json = JSONObject().put("key", "value")
        assertEquals("value", json.safeOptString("key", "fallback"))
    }

    @Test
    fun `safeOptString returns actual empty string value`() {
        val json = JSONObject().put("key", "")
        assertEquals("", json.safeOptString("key", "fallback"))
    }

    @Test
    fun `safeOptString returns fallback for non-string type`() {
        val json = JSONObject().put("key", 123)
        assertEquals("fallback", json.safeOptString("key", "fallback"))
    }

    @Test
    fun `safeOptString returns optString for non-string type without fallback`() {
        val json = JSONObject().put("key", 123)
        assertEquals("123", json.safeOptString("key"))
    }
}