package com.pebblerecorder.app.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepingAFileMovesItIntoTheBackup() {
        val src = tmp.newFile("a-rec.m4a").apply { writeText("audio") }
        val dest = File(tmp.newFolder("backups"), "a-rec.m4a")
        RecordingBackup.moveInto(src, dest)
        assertFalse(src.exists())
        assertEquals("audio", dest.readText())
    }

    @Test
    fun keepingAgainReplacesTheOlderBackup() {
        val dest = tmp.newFile("a-rec.m4a").apply { writeText("old") }
        val src = tmp.newFile("new.m4a").apply { writeText("new") }
        RecordingBackup.moveInto(src, dest)
        assertEquals("new", dest.readText())
    }
}
