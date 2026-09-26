package com.shadowself.sensors

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.shadowself.domain.model.AppCategory
import com.shadowself.domain.model.AppUsageEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls UsageStatsManager every 30 seconds to capture app-switching patterns.
 *
 * Requires PACKAGE_USAGE_STATS permission (granted via Settings, not runtime).
 * Package names are SHA-256 hashed before storage — app identities are never
 * stored in plaintext.
 */
@Singleton
class AppUsageCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm         = context.packageManager

    private val buffer    = ConcurrentLinkedQueue<AppUsageEvent>()
    private val MAX_BUFFER = 200

    private val hasher = MessageDigest.getInstance("SHA-256")

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _dataFlow = MutableStateFlow<List<AppUsageEvent>>(emptyList())
    val dataFlow: StateFlow<List<AppUsageEvent>> = _dataFlow.asStateFlow()

    // App category cache — avoid repeated PM lookups
    private val categoryCache = HashMap<String, AppCategory>()

    fun start() {
        pollJob = scope.launch {
            var lastPollMs = System.currentTimeMillis() - 30_000L
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                collectUsageEvents(lastPollMs, nowMs)
                lastPollMs = nowMs
                delay(30_000L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        buffer.clear()
    }

    private fun collectUsageEvents(fromMs: Long, toMs: Long) {
        val events = usageStats.queryEvents(fromMs, toMs) ?: return
        val event  = UsageEvents.Event()
        var lastForeground: Pair<String, Long>? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    lastForeground = Pair(event.packageName, event.timeStamp)

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    lastForeground?.let { (pkg, startMs) ->
                        if (pkg != event.packageName) return@let
                        val durationMs = event.timeStamp - startMs
                        if (durationMs < 500) return@let  // Ignore very brief flashes
                        if (buffer.size < MAX_BUFFER) {
                            buffer.add(AppUsageEvent(
                                timestampMs = event.timeStamp,
                                packageHash = hashPackage(pkg),
                                durationMs  = durationMs,
                                category    = getCategory(pkg)
                            ))
                        }
                    }
                    lastForeground = null
                }
            }
        }
        if (buffer.isNotEmpty()) _dataFlow.tryEmit(buffer.toList())
    }

    private fun hashPackage(pkg: String): String {
        val digest = hasher.digest(pkg.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun getCategory(pkg: String): AppCategory {
        categoryCache[pkg]?.let { return it }
        val category = try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            when (pm.getApplicationLabel(appInfo).toString().lowercase()) {
                in listOf("whatsapp", "telegram", "signal", "messages", "gmail") ->
                    AppCategory.COMMUNICATION
                in listOf("instagram", "twitter", "facebook", "tiktok", "snapchat") ->
                    AppCategory.SOCIAL
                in listOf("chrome", "firefox", "brave", "opera") ->
                    AppCategory.BROWSER
                in listOf("gpay", "phonepe", "paytm", "bank") ->
                    AppCategory.FINANCE
                else -> classifyByPackageName(pkg)
            }
        } catch (e: PackageManager.NameNotFoundException) { AppCategory.OTHER }
        categoryCache[pkg] = category
        return category
    }

    private fun classifyByPackageName(pkg: String): AppCategory = when {
        pkg.contains("mail") || pkg.contains("message") || pkg.contains("chat") ->
            AppCategory.COMMUNICATION
        pkg.contains("social") || pkg.contains("feed") ->
            AppCategory.SOCIAL
        pkg.contains("bank") || pkg.contains("pay") || pkg.contains("finance") ->
            AppCategory.FINANCE
        pkg.contains("browser") || pkg.contains("chrome") ->
            AppCategory.BROWSER
        pkg.startsWith("com.android") || pkg.startsWith("com.google.android") ->
            AppCategory.SYSTEM
        else -> AppCategory.OTHER
    }

    fun drainBuffer(): List<AppUsageEvent> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    fun getCurrentBuffer(): List<AppUsageEvent> = buffer.toList()
}
