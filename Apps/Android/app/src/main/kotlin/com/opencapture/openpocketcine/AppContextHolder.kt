package com.opencapture.openpocketcine

import android.content.Context

/** Application-scoped context for resolving strings outside @Composable. Initialized once in AppModel. */
object AppContextHolder {
    @Volatile
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }
}
