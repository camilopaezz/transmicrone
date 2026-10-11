package co.camilo.transmicrone.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true)

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    val settings = context.settingsDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        AppSettings(ThemeMode.entries.find { it.name == prefs[themeKey] } ?: ThemeMode.SYSTEM,
            prefs[dynamicKey] ?: true)
    }
    suspend fun setTheme(mode: ThemeMode) { context.settingsDataStore.edit { it[themeKey] = mode.name } }
    suspend fun setDynamicColor(enabled: Boolean) { context.settingsDataStore.edit { it[dynamicKey] = enabled } }
}
