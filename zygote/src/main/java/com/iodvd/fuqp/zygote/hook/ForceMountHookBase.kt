package com.iodvd.fuqp.zygote.hook

import java.util.concurrent.atomic.AtomicReference

abstract class ForceMountHookBase : IFrameworkHook {
    protected var lastForceMountedApp: AtomicReference<String?> = AtomicReference(null)
}
