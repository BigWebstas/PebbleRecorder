package com.pebblerecorder.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val everything = SettingsBackup.EXPORTED.values.flatten().toSet()

    @Test
    fun secretsAreNeverExported() {
        listOf("api_key", "openai_api_key", "claude_api_key", "token", "folder_uri").forEach {
            assertFalse("$it must not be exported", it in everything)
        }
    }

    @Test
    fun ordinarySettingsAreExported() {
        assertTrue("engine" in everything)
        assertTrue("max_minutes" in everything)
        assertTrue("whisper_model" in everything)
    }
}
