package com.funnyass.test

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志（默认关闭）：
 *  - 开关状态持久化在 SharedPreferences(qzxy_lite / log_enabled)，杀进程重进仍然保持
 *  - 开启后：logcat（tag QZXY）+ 内存环形缓冲 + 追加写 logs/qzyy.log
 *  - 文件到 1 MB 滚动为 qzyy.1.log，最多两份（≈2 MB 上限），不会无限占空间
 *  - 只有用户手动「清空日志」才会删除；关闭开关不会删除已有日志
 *
 * 每行格式：`MM-dd HH:mm:ss.SSS  <内容>`
 */
object Logger {
    private const val TAG = "QZXY"
    private const val SP = "qzxy_lite"
    private const val KEY_ENABLED = "log_enabled"
    private const val MAX_MEMORY_LINES = 1500
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val EXPORT_MAX_CHARS = 240_000

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var file: File? = null
    @Volatile private var status: String = "idle"
    @Volatile private var bootInfo: String = ""
    @Volatile private var started = false
    @Volatile private var enabled = false
    @Volatile private var appCtx: Context? = null

    /** 在 Application/首个 Activity 里调用一次即可，重复调用安全 */
    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        enabled = try {
            prefs(app).getBoolean(KEY_ENABLED, false)
        } catch (_: Exception) {
            false
        }
        bootInfo = buildString {
            append("机型=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" Android=").append(Build.VERSION.RELEASE)
            append("(API ").append(Build.VERSION.SDK_INT).append(')')
            append(" ABI=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            val vn = try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            } catch (_: Exception) {
                null
            }
            if (!vn.isNullOrBlank()) append(" 版本=").append(vn)
        }
        if (enabled) {
            openWriter()
            logInternal("===== 会话开始（日志默认开启，来自上次设置）=====")
            logInternal("环境: " + bootInfo)
        }
    }

    fun isEnabled(): Boolean = enabled

    /** 用户点「开启/关闭日志」，状态立即持久化，杀进程重进仍然生效 */
    @Synchronized
    fun setEnabled(ctx: Context, on: Boolean) {
        if (!started) init(ctx)
        enabled = on
        try {
            prefs(ctx.applicationContext ?: ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        } catch (_: Exception) {
        }
        if (on) {
            openWriter()
            logInternal("===== 日志已开启 =====")
            logInternal("环境: " + bootInfo)
        } else {
            logInternal("===== 日志已关闭 =====")
            closeWriter()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun logDir(): File? {
        val ctx = appCtx ?: return null
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir ?: return null
        val dir = File(base, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun openWriter() {
        try {
            val dir = logDir() ?: return
            val f = File(dir, "qzyy.log")
            rotateIfNeeded(f)
            file = f
            writer = BufferedWriter(FileWriter(f, true))
        } catch (e: Exception) {
            Log.w(TAG, "日志文件打开失败: " + e.message)
            writer = null
        }
    }

    private fun closeWriter() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    private fun rotateIfNeeded(f: File) {
        try {
            if (!f.exists() || f.length() < MAX_FILE_BYTES) return
            val old = File(f.parentFile, "qzyy.1.log")
            if (old.exists()) old.delete()
            f.renameTo(old)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun logInternal(s: String) {
        val line = fmt.format(Date()) + "  " + s
        Log.d(TAG, s)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_MEMORY_LINES) lines.removeFirst()
        }
        val w = writer ?: return
        try {
            w.write(line)
            w.newLine()
            w.flush()
        } catch (_: Exception) {
        }
    }

    /** 日志关着的时候直接丢弃，不产生任何文件与内存占用 */
    @Synchronized
    fun log(s: String) {
        if (!enabled) return
        logInternal(s)
    }

    @Synchronized
    fun status(s: String) {
        status = s
        if (enabled) logInternal("[status] " + s)
    }

    /** 内存缓冲全文（8080 端口 /log 用，保持旧行为） */
    @Synchronized
    fun getLog(): String = synchronized(lines) { lines.joinToString("\n") }

    @Synchronized
    fun getStatus(): String = status

    /** 「运行日志」面板显示用：只取最后 maxLines 行 */
    fun snapshot(maxLines: Int = 300): String {
        val copy = synchronized(lines) { lines.toList() }
        val from = if (copy.size > maxLines) copy.size - maxLines else 0
        return copy.subList(from, copy.size).joinToString("\n")
    }

    /** 手动清空：内存 + 两份文件全部删除；开关状态保持不变 */
    @Synchronized
    fun clearAll(ctx: Context) {
        closeWriter()
        try {
            val dir = logDir()
            if (dir != null) {
                File(dir, "qzyy.log").delete()
                File(dir, "qzyy.1.log").delete()
            }
        } catch (_: Exception) {
        }
        synchronized(lines) { lines.clear() }
        if (enabled) {
            openWriter()
            logInternal("===== 日志已清空 =====")
            logInternal("环境: " + bootInfo)
        }
    }

    /** 供 8080 端口 /clear 调用（不需要 Context） */
    fun clear() {
        appCtx?.let { clearAll(it) }
    }

    /** 导出的完整文本：文件日志（跨进程重启）+ 追加一段内存缓冲 */
    fun exportText(ctx: Context, note: String = ""): String {
        val sb = StringBuilder()
        sb.append("===== 睿智校园 日志导出 =====").append('\n')
        sb.append("导出时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("日志开关: ").append(if (enabled) "开启" else "关闭").append('\n')
        sb.append("环境: ").append(bootInfo).append('\n')
        sb.append("当前状态: ").append(status).append('\n')
        if (note.isNotBlank()) sb.append("备注: ").append(note).append('\n')
        val f = file
        sb.append("日志文件: ").append(f?.absolutePath ?: "（未初始化）").append('\n')
        sb.append("----------------------------------------\n")
        val content = try {
            if (f != null && f.exists()) f.readText() else ""
        } catch (e: Exception) {
            "（读取日志文件失败: " + e.message + "）"
        }
        val trimmed = if (content.length > EXPORT_MAX_CHARS) {
            "…（前部内容过长已省略）…\n" + content.substring(content.length - EXPORT_MAX_CHARS)
        } else content
        sb.append(trimmed)
        if (!trimmed.endsWith("\n")) sb.append('\n')
        sb.append("----------------------------------------\n")
        sb.append("----- 以下为内存缓冲（最近 ").append(MAX_MEMORY_LINES).append(" 行）-----\n")
        sb.append(snapshot(MAX_MEMORY_LINES))
        sb.append('\n')
        sb.append("===== 日志结束 =====").append('\n')
        return sb.toString()
    }

    /** 建议的文件名，便于直接发出来 */
    fun exportFileName(): String =
        "ruizhi-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
}