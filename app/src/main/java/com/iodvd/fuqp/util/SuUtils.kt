package com.iodvd.fuqp.util

import com.topjohnwu.superuser.Shell

object SuUtils {

    fun execPrivileged(cmd: String): Boolean {
        return Shell.isAppGrantedRoot() == true && Shell.cmd(cmd).exec().isSuccess
    }
}
