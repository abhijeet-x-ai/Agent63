package com.devstation.android.feature.editor

import com.devstation.android.feature.editor.service.EditorFileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnicodeEncodingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var fileManager: EditorFileManager

    @Before
    fun setup() {
        projectDir = tempFolder.newFolder("unicode_project")
        fileManager = EditorFileManager(projectDir)
    }

    @Test
    fun `preserves complex multilingual and emoji text without corruption`() {
        val multilingualContent = """
            // Multilingual Verification
            val english = "DevStation is a mobile IDE"
            val bengali = "বাংলা: দেবস্টেশন একটি শক্তিশালী মোবাইল কোডিং প্ল্যাটফর্ম"
            val hindi = "हिन्दी: देवस्टेशन मोबाइल विकास वातावरण"
            val chinese = "中文: 移动智能开发工作站"
            val japanese = "日本語: モバイルでコードを編集する"
            val arabic = "العربية: بيئة تطوير متكاملة على الهاتف"
            val emoji = "🚀 📱 💻 🔥 ✨ 🛠️"
        """.trimIndent()

        val file = File(projectDir, "multilingual.kt")
        val savedDoc = fileManager.saveFile(file, multilingualContent)
        assertEquals(multilingualContent, savedDoc.content)

        val readDoc = fileManager.readFile(file)
        assertEquals(multilingualContent, readDoc.content)

        assertTrue(readDoc.content.contains("দেবস্টেশন"))
        assertTrue(readDoc.content.contains("हिन्दी"))
        assertTrue(readDoc.content.contains("工作站"))
        assertTrue(readDoc.content.contains("日本語"))
        assertTrue(readDoc.content.contains("العربية"))
        assertTrue(readDoc.content.contains("🚀 📱 💻 🔥"))
    }
}
