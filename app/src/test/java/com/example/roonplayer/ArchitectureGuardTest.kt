package com.example.roonplayer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class ArchitectureGuardTest {

    @Test
    fun `main and network layers should not contain hardcoded discovery literals`() {
        val root = resolveProjectRoot()
        val targetFiles = mutableListOf(
            root.resolve("app/src/main/java/com/example/roonplayer/MainActivity.kt")
        )

        Files.newDirectoryStream(
            root.resolve("app/src/main/java/com/example/roonplayer/network"),
            "*.kt"
        ).use { stream ->
            for (path in stream) {
                targetFiles.add(path)
            }
        }

        val forbiddenPatterns = listOf(
            Regex("""\b9330\b"""),
            Regex("""\b9332\b"""),
            Regex("""\b9100\b"""),
            Regex("""\b9003\b"""),
            Regex("""239\.255\.90\.90"""),
            Regex("""255\.255\.255\.255""")
        )

        for (file in targetFiles) {
            val content = String(Files.readAllBytes(file), Charsets.UTF_8)
            for (pattern in forbiddenPatterns) {
                assertFalse(
                    "Found hardcoded literal '${pattern.pattern}' in ${root.relativize(file)}",
                    pattern.containsMatchIn(content)
                )
            }
        }
    }

    private fun resolveProjectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(6) {
            if (Files.exists(current.resolve("app/src/main/java"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        fail("Unable to locate project root containing app/src/main/java")
        throw IllegalStateException("unreachable")
    }
}
