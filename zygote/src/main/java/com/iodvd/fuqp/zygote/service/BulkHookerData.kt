package com.iodvd.fuqp.zygote.service

import android.util.Pair
import com.v7878.vmtools.HookTransformer
import java.lang.reflect.Executable

class ReturnValue(initialValue: Any? = null) {
    var replace: Boolean = false
        private set

    var result: Any? = initialValue
        set(newValue) {
            field = newValue
            replace = true
        }

    var throwable: Throwable? = null
}

data class HookElement(
    val impl: HookTransformer,
    val methodName: String,
    var method: Executable? = null,
    var memoryAddresses: Pair<Long, Long>? = null,
    var hookFinished: Boolean = false,
    val paramCount: Int = -1,
    /**
     * The exact parameter list to bind to, for when name and arity do not name
     * one method. Arity alone reselects blindly among equal-arity overloads,
     * which can attach the hook to a method other than the one whose signature
     * was checked - and the argument indices were checked against.
     */
    val paramTypes: List<Class<*>>? = null,
)
