package com.iodvd.fuqp.zygote.util

import android.os.ServiceManager
import android.provider.Settings
import com.iodvd.fuqp.common.Utils.binderLocalScope
import com.iodvd.fuqp.zygote.util.ServiceUtils.contentResolver
import com.iodvd.fuqp.zygote.util.ZLUtils.callMethod

object WebViewUtils {
    private val webViewService by lazy { ServiceManager.getService("webviewupdate") }

    fun getWebviewProvider(): String? = binderLocalScope {
        try {
            (callMethod(webViewService, "getCurrentWebViewPackageName") as? String).also { assert(it != null) }
        } catch (_: Throwable) {
            Settings.Global.getString(contentResolver, "webview_provider")
        }
    }
}
