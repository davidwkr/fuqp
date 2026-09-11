package com.iodvd.fuqp.ui.util

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Resources
import kotlinx.coroutines.flow.MutableSharedFlow
import com.iodvd.fuqp.BuildConfig
import com.iodvd.fuqp.R

fun Boolean.enabledString(resources: Resources, lower: Boolean = false): String {
    val returnedStr = if (this) resources.getString(R.string.enabled)
    else resources.getString(R.string.disabled)

    return if (lower) returnedStr.lowercase() else returnedStr
}

fun ActivityInfo.asComponentName() = ComponentName(packageName, name)

fun <T> MutableSharedFlow<T>.get() = replayCache.first()

fun dp2Px(res: Resources, dp: Int) = res.displayMetrics.density * dp

val isTestBuild get() = BuildConfig.VERSION_NAME.let { name ->
    name.count { it == '-' } != 1 ||
    name.count { it == '+' } > 0 ||
    name.split('-').last().length == 8
}
