package com.application.motium.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean(KEY_DARK_MODE, false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    // Default green color: #16a34a
    private val defaultColor = 0xFF16a34a.toInt()

    private val _primaryColor = MutableStateFlow(
        Color(prefs.getInt(KEY_PRIMARY_COLOR, defaultColor))
    )
    val primaryColor: StateFlow<Color> = _primaryColor.asStateFlow()

    // Favorite colors - default first slot is MockupGreen (#10B981)
    private val mockupGreen = 0xFF10B981.toInt()
    private val _favoriteColors = MutableStateFlow(
        listOf(
            Color(prefs.getInt(KEY_FAVORITE_COLOR_1, mockupGreen)),
            Color(prefs.getInt(KEY_FAVORITE_COLOR_2, defaultColor)),
            Color(prefs.getInt(KEY_FAVORITE_COLOR_3, defaultColor)),
            Color(prefs.getInt(KEY_FAVORITE_COLOR_4, defaultColor)),
            Color(prefs.getInt(KEY_FAVORITE_COLOR_5, defaultColor))
        )
    )
    val favoriteColors: StateFlow<List<Color>> = _favoriteColors.asStateFlow()

    fun toggleTheme() {
        val newValue = !_isDarkMode.value
        _isDarkMode.value = newValue
        prefs.edit().putBoolean(KEY_DARK_MODE, newValue).apply()
    }

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    fun setPrimaryColor(color: Color) {
        val colorInt = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        _primaryColor.value = color
        prefs.edit().putInt(KEY_PRIMARY_COLOR, colorInt).apply()
    }

    fun resetToDefaultColor() {
        setPrimaryColor(Color(defaultColor))
    }

    fun setFavoriteColor(index: Int, color: Color) {
        if (index < 0 || index >= 5) return

        val colorInt = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )

        val key = when (index) {
            0 -> KEY_FAVORITE_COLOR_1
            1 -> KEY_FAVORITE_COLOR_2
            2 -> KEY_FAVORITE_COLOR_3
            3 -> KEY_FAVORITE_COLOR_4
            4 -> KEY_FAVORITE_COLOR_5
            else -> return
        }

        prefs.edit().putInt(key, colorInt).apply()

        // Update the list
        val newList = _favoriteColors.value.toMutableList()
        newList[index] = color
        _favoriteColors.value = newList
    }

    fun getFavoriteColor(index: Int): Color? {
        if (index < 0 || index >= 5) return null
        return _favoriteColors.value.getOrNull(index)
    }

    companion object {
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_PRIMARY_COLOR = "primary_color"
        private const val KEY_FAVORITE_COLOR_1 = "favorite_color_1"
        private const val KEY_FAVORITE_COLOR_2 = "favorite_color_2"
        private const val KEY_FAVORITE_COLOR_3 = "favorite_color_3"
        private const val KEY_FAVORITE_COLOR_4 = "favorite_color_4"
        private const val KEY_FAVORITE_COLOR_5 = "favorite_color_5"

        @Volatile
        private var INSTANCE: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThemeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
