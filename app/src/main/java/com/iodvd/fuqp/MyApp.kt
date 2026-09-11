package com.iodvd.fuqp

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.iodvd.fuqp.receiver.AppChangeReceiver
import com.iodvd.fuqp.service.ConfigManager
import com.iodvd.fuqp.service.PrefManager
import com.iodvd.fuqp.service.ServiceClient
import com.iodvd.fuqp.util.ConfigUtils.Companion.getLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.zhanghai.android.appiconloader.AppIconLoader
import com.iodvd.fuqp.R

class MyApp : Application() {
    companion object {
        lateinit var fuqpApp: MyApp
    }

    val globalScope = CoroutineScope(Dispatchers.Default)
    val appIconLoader by lazy {
        val iconSize = resources.getDimensionPixelSize(R.dimen.app_icon_size)
        AppIconLoader(iconSize, false, this)
    }
    var updateDialogSkipped: Boolean = false

    @Suppress("DEPRECATION")
    fun loadConfiguration() {
        if (ServiceClient.serviceVersion > 0) {
            ConfigManager.init()

            AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)
            val config = resources.configuration
            config.setLocale(getLocale())
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fuqpApp = this
        AppChangeReceiver.register(this)

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ServiceClient.log(Log.ERROR, t.name, e.stackTraceToString())
            handler?.uncaughtException(t, e)
        }
    }
}
