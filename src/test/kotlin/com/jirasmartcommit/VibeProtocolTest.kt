package com.jirasmartcommit

import com.jirasmartcommit.util.VibeProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VibeProtocolTest {

    @Test
    fun `parseFileRequest extracts comma-separated paths`() {
        val response = "REQUEST_FILES: src/Main.kt, src/Util.kt , build.gradle.kts"
        val result = VibeProtocol.parseFileRequest(response)
        assertEquals(listOf("src/Main.kt", "src/Util.kt", "build.gradle.kts"), result)
    }

    @Test
    fun `parseFileRequest returns null when there is no marker`() {
        assertNull(VibeProtocol.parseFileRequest("Sure, I'll need to look at a few files."))
    }

    @Test
    fun `parseFileRequest returns null when response also contains file blocks`() {
        val response = """
            REQUEST_FILES: src/Main.kt

            ===FILE: src/Main.kt===
            fun main() {}
            ===END_FILE===
        """.trimIndent()
        assertNull(VibeProtocol.parseFileRequest(response))
    }

    @Test
    fun `parseFileChanges extracts a single file block`() {
        val response = """
            Here's the change:

            ===FILE: src/App.kt===
            class App {
                fun hello() = "world"
            }
            ===END_FILE===
        """.trimIndent()

        val changes = VibeProtocol.parseFileChanges(response)
        assertEquals(1, changes.size)
        val content = changes["src/App.kt"]
        assertTrue(content != null && content.contains("class App"))
        assertTrue(content!!.endsWith("\n"), "Content should be normalized with trailing newline")
    }

    @Test
    fun `parseFileChanges extracts multiple file blocks`() {
        val response = """
            ===FILE: a.kt===
            val a = 1
            ===END_FILE===

            Some prose between blocks.

            ===FILE: dir/b.kt===
            val b = 2
            ===END_FILE===
        """.trimIndent()

        val changes = VibeProtocol.parseFileChanges(response)
        assertEquals(setOf("a.kt", "dir/b.kt"), changes.keys)
        assertEquals("val a = 1\n", changes["a.kt"])
        assertEquals("val b = 2\n", changes["dir/b.kt"])
    }

    @Test
    fun `parseFileChanges returns empty map when no markers present`() {
        val changes = VibeProtocol.parseFileChanges("No code here, just chatting.")
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `parseFileChanges trims path whitespace`() {
        val response = """
            ===FILE:    src/with-spaces.kt   ===
            content
            ===END_FILE===
        """.trimIndent()
        val changes = VibeProtocol.parseFileChanges(response)
        assertEquals(setOf("src/with-spaces.kt"), changes.keys)
    }
}
