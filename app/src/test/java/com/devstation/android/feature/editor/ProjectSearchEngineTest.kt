package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.service.EditorFileManager
import com.devstation.android.feature.editor.service.ProjectSearchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class ProjectSearchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var searchEngine: ProjectSearchEngine

    @Before
    fun setup() {
        projectDir = tempFolder.newFolder("search_project")
        val fileManager = EditorFileManager(projectDir)
        searchEngine = ProjectSearchEngine(projectDir, fileManager)

        // Create sample project files
        val srcDir = File(projectDir, "src").apply { mkdirs() }
        File(srcDir, "App.tsx").writeText(
            """
            import React, { useState } from 'react';
            export function App() {
                const [count, setCount] = useState(0);
                return <div>{count}</div>;
            }
            """.trimIndent(),
            StandardCharsets.UTF_8
        )

        File(srcDir, "Counter.tsx").writeText(
            """
            // Counter component using useState
            export const Counter = () => null;
            """.trimIndent(),
            StandardCharsets.UTF_8
        )

        // Create excluded directory files
        val gitDir = File(projectDir, ".git").apply { mkdirs() }
        File(gitDir, "COMMIT_EDITMSG").writeText("useState in commit message", StandardCharsets.UTF_8)

        val nodeModulesDir = File(projectDir, "node_modules").apply { mkdirs() }
        File(nodeModulesDir, "package.json").writeText("useState in dependency", StandardCharsets.UTF_8)
    }

    @Test
    fun `searches text across project files and skips excluded directories`() = runBlocking {
        val results = searchEngine.search("useState")

        // Should find in App.tsx (2 occurrences) and Counter.tsx (1 occurrence)
        // Should NOT find in .git or node_modules
        assertEquals(3, results.size)
        assertTrue(results.any { it.relativePath.contains("App.tsx") })
        assertTrue(results.any { it.relativePath.contains("Counter.tsx") })
        assertTrue(results.none { it.relativePath.contains(".git") })
        assertTrue(results.none { it.relativePath.contains("node_modules") })
    }

    @Test
    fun `honors case sensitivity during search`() = runBlocking {
        val caseSensitive = searchEngine.search("USESTATE", isCaseSensitive = true)
        assertEquals(0, caseSensitive.size)

        val caseInsensitive = searchEngine.search("USESTATE", isCaseSensitive = false)
        assertEquals(3, caseInsensitive.size)
    }

    @Test
    fun `honors whole word matching`() = runBlocking {
        val wholeWord = searchEngine.search("use", isWholeWord = true)
        // "useState" should not match whole word "use"
        assertEquals(0, wholeWord.size)

        val subString = searchEngine.search("use", isWholeWord = false)
        assertTrue(subString.isNotEmpty())
    }
}
