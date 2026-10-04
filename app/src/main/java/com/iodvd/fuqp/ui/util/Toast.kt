package com.iodvd.fuqp.ui.util

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.iodvd.fuqp.R

fun Fragment.showToast(@StringRes resId: Int, duration: Int = Snackbar.LENGTH_SHORT) {
    Snackbar.make(requireActivity().window.decorView, resId, duration).apply {
        if (duration == Snackbar.LENGTH_INDEFINITE) {
            setCloseIconVisible(true)
            setCloseIconResource(R.drawable.check_24px)
        }
    }.show()
}

fun Fragment.showNeedRebootToast() = showToast(
    R.string.settings_need_reboot,
    Snackbar.LENGTH_LONG,
)
