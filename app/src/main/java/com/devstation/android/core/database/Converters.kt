package com.devstation.android.core.database

import androidx.room.TypeConverter
import com.devstation.android.core.model.AppTheme
import com.devstation.android.core.model.MessageRole

class Converters {
    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = try {
        MessageRole.valueOf(value)
    } catch (e: Exception) {
        MessageRole.USER
    }

    @TypeConverter
    fun fromAppTheme(theme: AppTheme): String = theme.name

    @TypeConverter
    fun toAppTheme(value: String): AppTheme = try {
        AppTheme.valueOf(value)
    } catch (e: Exception) {
        AppTheme.DARK
    }
}
