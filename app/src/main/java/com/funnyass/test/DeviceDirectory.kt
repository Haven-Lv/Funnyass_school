package com.funnyass.test

import android.content.Context
import com.funnyass.test.data.DeviceInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * 本机设备档案：MAC → 设备/房间名。
 *
 * 为什么不用蓝牙广播名：`KLCXKJ-Water` 对所有水表都一样，且很多表根本不广播名字。
 * 这里缓存的是服务器 `device/mac` 接口返回的 roomName / devName，稳定且人眼可读，
 * 第一次手动连过一次之后，扫描列表就能直接显示「工程学院北区-E2-1层-103」。
 *
 * 记录挂在 `qzxy_lite` 这个 SharedPreferences 里（与 Session 同一个文件，不额外增加文件）。
 */
data class DeviceRecord(
    val mac: String? = null,
    val room: String? = null,
    val devName: String? = null,
    val devTypeName: String? = null,
    val devId: Int = 0,
    val protocol: String? = null,
    val lastSeen: Long = 0L,
    val lastFail: Long = 0L,
    val failCount: Int = 0
) {
    /** 列表里优先显示房间名，其次设备名 */
    fun label(): String {
        val r = room?.trim()
        if (!r.isNullOrEmpty()) return r
        val n = devName?.trim()
        if (!n.isNullOrEmpty()) return n
        return ""
    }

    fun subtitle(): String {
        val kind = devTypeName?.trim().orEmpty()
        val id = if (devId > 0) "编号 " + devId else ""
        return listOf(kind, id).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

object DeviceDirectory {
    private const val SP = "qzxy_lite"
    private const val KEY = "device_directory_v1"

    /** 记录有效期：超过这个时间的档案不再显示（换表/改名后避免一直套用旧名字） */
    private const val TTL_MS = 180L * 24 * 3600 * 1000

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, DeviceRecord>>() {}.type

    fun norm(mac: String?): String = (mac ?: "").trim().uppercase(Locale.US)

    @Synchronized
    fun load(ctx: Context): MutableMap<String, DeviceRecord> {
        return try {
            val s = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return mutableMapOf()
            val m: MutableMap<String, DeviceRecord>? = gson.fromJson(s, type)
            m ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun save(ctx: Context, map: MutableMap<String, DeviceRecord>) {
        try {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, gson.toJson(map))
                .apply()
        } catch (_: Exception) {
        }
    }

    /** 取档案；过期的视为不存在 */
    fun lookup(ctx: Context, mac: String?): DeviceRecord? {
        val key = norm(mac)
        if (key.isEmpty()) return null
        val rec = load(ctx)[key] ?: return null
        val seen = rec.lastSeen
        if (seen > 0 && System.currentTimeMillis() - seen > TTL_MS) return null
        return rec
    }

    fun labelOf(ctx: Context, mac: String?): String? =
        lookup(ctx, mac)?.label()?.takeIf { it.isNotEmpty() }

    /** 成功查询到设备信息 / 成功连接后写入或合并档案 */
    @Synchronized
    fun remember(ctx: Context, mac: String?, info: DeviceInfo?, protocol: String? = null): DeviceRecord? {
        val key = norm(mac)
        if (key.isEmpty()) return null
        val map = load(ctx)
        val old = map[key]
        val rec = DeviceRecord(
            mac = key,
            room = info?.roomName?.trim()?.takeIf { it.isNotEmpty() } ?: old?.room,
            devName = info?.devName?.trim()?.takeIf { it.isNotEmpty() } ?: old?.devName,
            devTypeName = info?.devTypeName?.trim()?.takeIf { it.isNotEmpty() } ?: old?.devTypeName,
            devId = if ((info?.devID ?: 0) > 0) info!!.devID else (old?.devId ?: 0),
            protocol = protocol?.takeIf { it.isNotEmpty() } ?: old?.protocol,
            lastSeen = System.currentTimeMillis(),
            lastFail = old?.lastFail ?: 0L,
            failCount = 0
        )
        map[key] = rec
        save(ctx, map)
        Logger.log(
            "设备档案写入 mac=" + key +
                " 名称=" + (rec.label().ifEmpty { "-" }) +
                " 类型=" + (rec.devTypeName ?: "-") +
                " 编号=" + rec.devId
        )
        return rec
    }

    /** 连接失败打点，列表里可以提示「上次未连上」 */
    @Synchronized
    fun noteFail(ctx: Context, mac: String?) {
        val key = norm(mac)
        if (key.isEmpty()) return
        val map = load(ctx)
        val old = map[key] ?: DeviceRecord(mac = key)
        map[key] = old.copy(lastFail = System.currentTimeMillis(), failCount = old.failCount + 1)
        save(ctx, map)
    }

    @Synchronized
    fun forget(ctx: Context, mac: String?) {
        val key = norm(mac)
        if (key.isEmpty()) return
        val map = load(ctx)
        map.remove(key)
        save(ctx, map)
        Logger.log("设备档案删除 mac=" + key)
    }
}