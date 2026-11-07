package dev.nekuz.dayboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceRulesTest {
    private val sourceRoot = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
    private val sources = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
    private val pure = setOf(
        "Model.kt",
        "Recurrence.kt",
        "Reminders.kt",
        "Streaks.kt",
        "ScheduleText.kt",
        "Json.kt",
        "BoardCodec.kt",
        "BoardFile.kt",
        "Boards.kt",
        "ForeignText.kt",
    )

    @Test
    fun allPureFilesExist() {
        assertEquals(pure, sources.map { it.name }.filter { it in pure }.toSet())
    }

    @Test
    fun pureFilesHaveNoAndroidImports() {
        sources.filter { it.name in pure }.forEach { file ->
            assertFalse(file.name, Regex("(?m)^import android").containsMatchIn(file.readText()))
            assertFalse(file.name, file.readText().contains("android.os"))
        }
    }

    @Test
    fun noCommentsInSources() {
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                assertFalse("${file.name}:${index + 1}", trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*"))
                assertFalse("${file.name}:${index + 1}", line.contains(" // "))
            }
        }
    }

    @Test
    fun noForbiddenApis() {
        val forbidden = listOf(
            "android.util.Log",
            "Log.d(",
            "Log.e(",
            "println(",
            "printStackTrace",
            "WebView",
            "rawQuery",
            "execSQL",
            "SQLite",
            "Runtime.getRuntime",
            "ProcessBuilder",
            "DexClassLoader",
            "ClassLoader",
            "Class.forName",
            "java.net.",
            "HttpURLConnection",
            "Socket",
        )
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { assertFalse("${file.name}: $it", text.contains(it)) }
        }
    }

    @Test
    fun noThirdPartyDependencies() {
        val build = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() && it.readText().contains("dependencies") }
        val dependencies = build.readText().substringAfter("dependencies {").substringBefore("}")
        val lines = dependencies.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("testImplementation(\"junit:junit:4.13.2\")"), lines)
    }

    @Test
    fun sdkLevelsMatchBrief() {
        val build = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.exists() && it.readText().contains("compileSdk") }.readText()
        assertTrue(build.contains("minSdk = 29"))
        assertTrue(build.contains("targetSdk = 35"))
        assertTrue(build.contains("compileSdk = 35"))
    }
}
