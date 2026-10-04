package com.iodvd.fuqp

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.iodvd.fuqp.receiver.AppChangeReceiver
import com.iodvd.fuqp.service.ConfigManager
import com.iodvd.fuqp.service.PrefManager
import com.iodvd.fuqp.service.ServiceClient
import com.iodvd.fuqp.util.ConfigUtils.getLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class MyApp : Application() {
    companion object {
        lateinit var fuqpApp: MyApp
    }

    val globalScope = CoroutineScope(Dispatchers.Default)
    var updateDialogSkipped: Boolean = false

    fun loadConfiguration() {
        if (ServiceClient.serviceVersion > 0) {
            ConfigManager.init()
        }
    }

    fun loadPreferences() {
        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        reloadLocale(getLocale())
    }

    @Suppress("DEPRECATION")
    fun reloadLocale(locale: Locale) {
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    override fun onCreate() {
        super.onCreate()
        fuqpApp = this
        loadPreferences()
        AppChangeReceiver.register(this)

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ServiceClient.log(Log.ERROR, t.name, e.stackTraceToString())
            handler?.uncaughtException(t, e)
        }
    }
}
