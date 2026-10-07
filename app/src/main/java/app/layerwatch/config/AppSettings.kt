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

    private companion object {
        val WATCH = booleanPreferencesKey("watch_in_background")
    }
}
