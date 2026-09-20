package com.devstation.android.feature.editor.service

import com.devstation.android.feature.editor.model.EditorLanguage
import java.io.File

object EditorLanguageDetector {

    fun detect(file: File): EditorLanguage {
        return detect(file.name)
    }

    fun detect(fileNameOrPath: String): EditorLanguage {
        val fileName = File(fileNameOrPath).name
        return EditorLanguage.fromFileName(fileName)
    }

    fun detectByExtension(extension: String): EditorLanguage {
        return EditorLanguage.fromExtension(extension)
    }
}
