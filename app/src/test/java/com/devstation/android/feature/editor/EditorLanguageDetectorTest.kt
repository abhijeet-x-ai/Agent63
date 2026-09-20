package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.model.EditorLanguage
import com.devstation.android.feature.editor.service.EditorLanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLanguageDetectorTest {

    @Test
    fun `detects common programming languages by file extension`() {
        assertEquals(EditorLanguage.KOTLIN, EditorLanguageDetector.detect("MainActivity.kt"))
        assertEquals(EditorLanguage.KOTLIN, EditorLanguageDetector.detect("build.gradle.kts"))
        assertEquals(EditorLanguage.JAVA, EditorLanguageDetector.detect("User.java"))
        assertEquals(EditorLanguage.JAVASCRIPT, EditorLanguageDetector.detect("index.js"))
        assertEquals(EditorLanguage.TYPESCRIPT, EditorLanguageDetector.detect("app.tsx"))
        assertEquals(EditorLanguage.PYTHON, EditorLanguageDetector.detect("script.py"))
        assertEquals(EditorLanguage.RUST, EditorLanguageDetector.detect("main.rs"))
        assertEquals(EditorLanguage.GO, EditorLanguageDetector.detect("server.go"))
        assertEquals(EditorLanguage.C, EditorLanguageDetector.detect("util.c"))
        assertEquals(EditorLanguage.CPP, EditorLanguageDetector.detect("engine.cpp"))
        assertEquals(EditorLanguage.DART, EditorLanguageDetector.detect("main.dart"))
        assertEquals(EditorLanguage.SWIFT, EditorLanguageDetector.detect("App.swift"))
    }

    @Test
    fun `detects web markup and data serialization formats`() {
        assertEquals(EditorLanguage.HTML, EditorLanguageDetector.detect("index.html"))
        assertEquals(EditorLanguage.CSS, EditorLanguageDetector.detect("styles.scss"))
        assertEquals(EditorLanguage.JSON, EditorLanguageDetector.detect("package.json"))
        assertEquals(EditorLanguage.XML, EditorLanguageDetector.detect("AndroidManifest.xml"))
        assertEquals(EditorLanguage.YAML, EditorLanguageDetector.detect("docker-compose.yml"))
        assertEquals(EditorLanguage.SQL, EditorLanguageDetector.detect("schema.sql"))
        assertEquals(EditorLanguage.MARKDOWN, EditorLanguageDetector.detect("README.md"))
        assertEquals(EditorLanguage.SHELL, EditorLanguageDetector.detect("setup.sh"))
    }

    @Test
    fun `detects special filenames like Dockerfile and Makefile`() {
        assertEquals(EditorLanguage.SHELL, EditorLanguageDetector.detect("Dockerfile"))
        assertEquals(EditorLanguage.SHELL, EditorLanguageDetector.detect("Makefile"))
    }

    @Test
    fun `falls back to plain text for unknown extensions`() {
        assertEquals(EditorLanguage.TEXT, EditorLanguageDetector.detect("notes.unknownext"))
        assertEquals(EditorLanguage.TEXT, EditorLanguageDetector.detect("LICENSE"))
        assertEquals(EditorLanguage.TEXT, EditorLanguageDetector.detect(".gitignore"))
    }
}
