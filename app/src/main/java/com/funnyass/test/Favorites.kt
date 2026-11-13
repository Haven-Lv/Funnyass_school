package com.funnyass.test

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/** 收藏的一台设备（按 MAC 唯一） */
data class FavoriteDevice(
    val mac: String? = null,
    val label: String? = null,
    val addedAt: Long = 0L
) {
    fun name(): String = label?.trim().orEmpty()
}

/**
 * 收藏设备：独立于「设备档案」的 TTL，用户手动收藏的永不过期，
 * 直到用户自己点「取消收藏」。
 */
object Favorites {
    private const val SP = "qzxy_lite"
    private const val KEY = "favorite_devices_v1"

    private val gson = Gson()
    private val type = object : TypeToken<MutableList<FavoriteDevice>>() {}.type

    fun norm(mac: String?): String = (mac ?: "").trim().uppercase(Locale.US)

    @Synchronized
    fun load(ctx: Context): MutableList<FavoriteDevice> {
        return try {
            val s = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return mutableListOf()
            val list: MutableList<FavoriteDevice>? = gson.fromJson(s, type)
            list ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: MutableList<FavoriteDevice>) {
        try {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, gson.toJson(list))
                .apply()
        } catch (_: Exception) {
        }
    }

    fun isFavorite(ctx: Context, mac: String?): Boolean {
        val key = norm(mac)
        if (key.isEmpty()) return false
        return load(ctx).any { norm(it.mac) == key }
    }

    /** 收藏；已存在时只刷新名字 */
    @Synchronized
    fun add(ctx: Context, mac: String?, label: String?) {
        val key = norm(mac)
        if (key.isEmpty()) return
        val list = load(ctx)
        val idx = list.indexOfFirst { norm(it.mac) == key }
        val name = label?.trim().orEmpty()
        if (idx >= 0) {
            if (name.isNotEmpty()) list[idx] = list[idx].copy(label = name)
        } else {
            list.add(FavoriteDevice(key, name, System.currentTimeMillis()))
        }
        save(ctx, list)
        Logger.log("收藏设备 mac=" + key + " 名称=" + (name.ifEmpty { "-" }))
    }

    /** 档案/设备详情更新后，刷新收藏里显示的名字 */
    @Synchronized
    fun rename(ctx: Context, mac: String?, label: String?) {
        val key = norm(mac)
        val name = label?.trim().orEmpty()
        if (key.isEmpty() || name.isEmpty()) return
        val list = load(ctx)
        val idx = list.indexOfFirst { norm(it.mac) == key }
        if (idx < 0) return
        if (list[idx].label == name) return
        list[idx] = list[idx].copy(label = name)
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, mac: String?) {
        val key = norm(mac)
        if (key.isEmpty()) return
        val list = load(ctx)
        val removed = list.removeAll { norm(it.mac) == key }
        if (removed) {
            save(ctx, list)
            Logger.log("取消收藏 mac=" + key)
        }
    }
}