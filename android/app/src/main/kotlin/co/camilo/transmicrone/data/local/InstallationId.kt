package co.camilo.transmicrone.data.local

import android.content.Context
import java.util.UUID

object InstallationId {
    @Synchronized
    fun getOrCreate(context: Context): String {
        val preferences = context.getSharedPreferences("feed-installation", Context.MODE_PRIVATE)
        preferences.getString("uuid", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString("uuid", id).commit()) {
            "Could not persist feed installation ID"
        }
        return id
    }
}
