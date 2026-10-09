package app.layerwatch.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** User preferences that outlive "forget printer". */
class AppSettings(private val context: Context) {
    /** Keep watching the printer with the app closed (foreground service + notifications). */
    val watchInBackground: Flow<Boolean> = context.settingsStore.data.map { it[WATCH] ?: true }

    suspend fun setWatchInBackground(on: Boolean) {
        context.settingsStore.edit { it[WATCH] = on }
    }

    /** Stop the screen from sleeping while the dashboard is open. */
    val keepScreenOn: Flow<Boolean> = context.settingsStore.data.map { it[SCREEN_ON] ?: false }

    suspend fun setKeepScreenOn(on: Boolean) {
        context.settingsStore.edit { it[SCREEN_ON] = on }
    }

    private companion object {
        val WATCH = booleanPreferencesKey("watch_in_background")
        val SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
