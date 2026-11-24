package com.aifindres.lite.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SiteRepository {
    @Volatile private var cache: List<Site>? = null

    fun all(context: Context): List<Site> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val list = try {
                context.assets.open("sites.json").bufferedReader(Charsets.UTF_8).use { r ->
                    Gson().fromJson<List<Site>>(r.readText(), object : TypeToken<List<Site>>(){}.type)
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            cache = list
            return list
        }
    }
}
