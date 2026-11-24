package com.aifindres.lite.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("resfind", 0)

    fun enabledIds(all: List<Site>): Set<Int> {
        val ss = sp.getStringSet(KEY_SITES, null) ?: return all.map { it.id }.toSet()
        return ss.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setEnabledIds(ids: Set<Int>) {
        sp.edit().putStringSet(KEY_SITES, ids.map { it.toString() }.toSet()).apply()
    }

    fun clearSites() { sp.edit().remove(KEY_SITES).apply() }

    companion object { private const val KEY_SITES = "sites_enabled" }
}
