package com.devstation.android.feature.editor.service

import androidx.compose.ui.graphics.Color
import com.devstation.android.core.ui.theme.AmberAccent
import com.devstation.android.core.ui.theme.BlueAccent
import com.devstation.android.core.ui.theme.DarkBg
import com.devstation.android.core.ui.theme.DarkOutline
import com.devstation.android.core.ui.theme.DarkSurfaceVariant
import com.devstation.android.core.ui.theme.EmeraldAccent
import com.devstation.android.core.ui.theme.LightBg
import com.devstation.android.core.ui.theme.LightBlueAccent
import com.devstation.android.core.ui.theme.LightOutline
import com.devstation.android.core.ui.theme.LightSurfaceVariant
import com.devstation.android.core.ui.theme.RedAccent

data class EditorColorScheme(
    val background: Color,
    val text: Color,
    val lineNumber: Color,
    val currentLineBackground: Color,
    val selectionBackground: Color,
    val searchMatchBackground: Color,
    val activeSearchMatchBackground: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val punctuation: Color,
    val annotation: Color
) {
    companion object {
        val Dark = EditorColorScheme(
            background = DarkBg,
            text = Color(0xFFC9D1D9),
            lineNumber = Color(0xFF6E7681),
            currentLineBackground = Color(0xFF161B22),
            selectionBackground = Color(0xFF264F78),
            searchMatchBackground = Color(0x66D29922),
            activeSearchMatchBackground = Color(0xCCF0883E),
            keyword = Color(0xFFFF7B72),
            string = Color(0xFFA5D6FF),
            number = Color(0xFF79C0FF),
            comment = Color(0xFF8B949E),
            type = Color(0xFFFFA657),
            function = Color(0xFFD2A8FF),
            punctuation = Color(0xFF7EE787),
            annotation = AmberAccent
        )

        val Light = EditorColorScheme(
            background = LightBg,
            text = Color(0xFF24292F),
            lineNumber = Color(0xFF8C959F),
            currentLineBackground = Color(0xFFF1F4F8),
            selectionBackground = Color(0xFFB4D5FE),
            searchMatchBackground = Color(0x66FFD33D),
            activeSearchMatchBackground = Color(0xCCFA7A1E),
            keyword = Color(0xFFCF222E),
            string = Color(0xFF0A3069),
            number = Color(0xFF0550AE),
            comment = Color(0xFF6E7781),
            type = Color(0xFF953800),
            function = Color(0xFF8250DF),
            punctuation = Color(0xFF116329),
            annotation = Color(0xFFB08800)
        )
    }
}
