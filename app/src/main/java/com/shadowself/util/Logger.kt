package com.shadowself.util

import android.util.Log

object Logger {
    private const val GLOBAL_TAG = "ShadowSelf"
    private var debugEnabled = true

    fun d(tag: String, msg: String) {
        if (debugEnabled) Log.d(GLOBAL_TAG, "[$tag] $msg")
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(GLOBAL_TAG, "[$tag] $msg", throwable)
    }

    fun disableDebugLogs() { debugEnabled = false }
}
