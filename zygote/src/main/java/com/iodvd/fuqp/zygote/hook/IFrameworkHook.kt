package com.iodvd.fuqp.zygote.hook

interface IFrameworkHook {
    @Suppress("PropertyName")
    val TAG: String

    fun load()
}
