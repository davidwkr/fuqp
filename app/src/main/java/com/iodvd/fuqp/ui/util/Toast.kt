package com.iodvd.fuqp.ui.util

import android.widget.Toast
import androidx.annotation.StringRes
import com.iodvd.fuqp.MyApp.Companion.fuqpApp

fun showToast(@StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(fuqpApp, resId, duration).show()
}

fun showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(fuqpApp, text, duration).show()
}
