package com.iodvd.fuqp.zygote.hook

import com.iodvd.fuqp.zygote.service.UserService

interface IFrameworkHook {
    @Suppress("PropertyName")
    val TAG: String

    val service get() = UserService.service!!
    val hooker get() = service.hooker
    val dataHolder get() = service.dataHolder
    val pms get() = service.pms
    val config get() = service.config
    val systemApps get() = service.systemApps

    fun load()
}
