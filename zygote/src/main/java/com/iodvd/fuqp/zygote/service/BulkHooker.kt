package com.iodvd.fuqp.zygote.service

import android.os.Build
import com.v7878.unsafe.ArtMethodUtils
import com.v7878.unsafe.Reflection
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.EmulatedStackFrame.RETURN_VALUE_IDX
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import com.iodvd.fuqp.zygote.ZygoteEntry
import com.iodvd.fuqp.zygote.service.FUQPService.Companion.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logE
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logV
import com.iodvd.fuqp.zygote.util.ServiceUtils
import com.iodvd.fuqp.zygote.util.ZLUtils.dumpArgs
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZLUtils.setReturnValue
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class BulkHooker private constructor() {
    companion object {
        val instance: BulkHooker by lazy { BulkHooker() }
        const val PARAMETER_COUNT_UNKNOWN = -1
    }

    internal val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<HookElement>>()

    fun isHookAvailable(clazz: String, methodName: String): Boolean {
        return hooks[clazz]?.any { it.methodName == methodName } ?: false
    }

    /** True when the hook is installed; false when it is disabled or unbindable. */
    private fun addHook(
        clazz: String,
        methodName: String,
        paramCount: Int,
        paramTypes: List<Class<*>>?,
        impl: HookTransformer,
    ): Boolean {
        val inDisabledHooks = service?.config?.disabledHooks?.any {
            clazz == it.className &&
                    methodName == it.methodName &&
                    paramCount == it.argumentCount
        }

        if (inDisabledHooks == true) {
            logI(ZygoteEntry.TAG) { "Disabled hook: $clazz -> $methodName($paramCount)" }
            return false
        }

        val element = HookElement(
            impl = impl,
            methodName = methodName,
            paramCount = paramCount,
            paramTypes = paramTypes,
        )

        if (applyHook(clazz, element)) {
            // Deliberately not computeIfAbsent: it holds the bin lock across the mapping
            // function and is documented as non-reentrant. applyHook above has already armed
            // the hook, so by this point a hooked method can fire on another thread and reach
            // this same map. putIfAbsent takes no user code under the lock, so that cannot
            // wedge the bin; the loser of a race just drops its unused list.
            val existing = hooks[clazz]
                ?: CopyOnWriteArrayList<HookElement>().let { hooks.putIfAbsent(clazz, it) ?: it }
            existing.add(element)
            return true
        }

        logI(ZygoteEntry.TAG) { "Invalid hook removed: $clazz -> $methodName($paramCount)" }
        return false
    }

    internal fun hookBefore(
        clazz: String,
        methodName: String,
        paramCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, paramCount, paramTypes) { original, frame ->
        val value = ReturnValue()

        try {
            hook(methodName, frame, value)
        } catch (it: Throwable) {
            logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
        }

        if (!value.replace) {
            try {
                invokeExactCompat(clazz, methodName, original, frame, value)
            } catch (it: Throwable) {
                logD(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on original function" }
                value.throwable = it
            }
        }

        value.throwable?.let {
            ServiceUtils.clearStackTraces(it)

            throw it
        }

        if (value.replace) {
            frame.setReturnValue(value.result)
        }
    }

    /**
     * [hookBefore] plus a guaranteed teardown: [after] runs once the original has returned or
     * thrown, on the same thread that ran [hook].
     *
     * Registering a [hookBefore] and a [hookAfter] on one method would mean two transformers on
     * the same target, so this is the only way to bracket a single invocation. That makes it
     * safe to park per-call state in a ThreadLocal from [hook] - binder threads are pooled, so
     * state that outlived its call would be read by an unrelated later query on the same thread.
     */
    internal fun hookAround(
        clazz: String,
        methodName: String,
        paramCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        after: () -> Unit,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, paramCount, paramTypes) { original, frame ->
        val value = ReturnValue()

        try {
            hook(methodName, frame, value)
        } catch (it: Throwable) {
            logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
        }

        try {
            if (!value.replace) {
                try {
                    invokeExactCompat(clazz, methodName, original, frame, value)
                } catch (it: Throwable) {
                    logD(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on original function" }
                    value.throwable = it
                }
            }
        } finally {
            try {
                after()
            } catch (it: Throwable) {
                logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook teardown" }
            }
        }

        value.throwable?.let {
            ServiceUtils.clearStackTraces(it)

            throw it
        }

        if (value.replace) {
            frame.setReturnValue(value.result)
        }
    }

    internal fun hookAfter(
        clazz: String,
        methodName: String,
        paramCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, paramCount, paramTypes) { original, frame ->
        val value = ReturnValue()

        try {
            invokeExactCompat(clazz, methodName, original, frame, value)
        } catch (it: Throwable) {
            logD(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on original function" }
            value.throwable = it
        }

        if (value.throwable == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            value.result = frame.accessor().getValue(RETURN_VALUE_IDX)
        }

        try {
            hook(methodName, frame, value)
        } catch (it: Throwable) {
            logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
        }

        value.throwable?.let {
            ServiceUtils.clearStackTraces(it)

            throw it
        }

        frame.setReturnValue(value.result)
    }

    private fun applyHook(
        clazz: String,
        element: HookElement,
        loader: ClassLoader? = SystemServerHook.classLoader,
    ): Boolean {
        var curClazz: Class<*>?
        try {
            curClazz = Class.forName(clazz, true, loader)
        } catch (ex: ClassNotFoundException) {
            logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
            return false
        }

        fun applyForClass(clazz: Class<*>?) {
            val executables = Reflection.getHiddenExecutables(clazz).filter { executable ->
                if (element.methodName == executable.name) {
                    // The exact list when there is one, because arity can name
                    // more than one method; arity otherwise.
                    element.paramTypes?.let {
                        return@filter it == executable.parameterTypes.toList()
                    }

                    if (element.paramCount >= 0) {
                        return@filter element.paramCount == executable.parameterCount
                    }

                    return@filter true
                }

                return@filter false
            }.sortedWith { v1, v2 ->
                v1.parameterCount.compareTo(v2.parameterCount)
            }

            for (executable in executables) {
                if (!element.hookFinished) {
                    logD(ZygoteEntry.TAG) { "Hooked: $executable" }

                    val memoryAddresses = Hooks.hook(
                        executable, Hooks.EntryPointType.DIRECT,
                        element.impl, Hooks.EntryPointType.DIRECT
                    )

                    logV(ZygoteEntry.TAG) { "Memory address map: $memoryAddresses" }

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        element.memoryAddresses = memoryAddresses
                        element.method = executable
                    }

                    element.hookFinished = true
                    break
                }
            }
        }

        while (
            !element.hookFinished &&
            curClazz != null &&
            curClazz.javaClass.simpleName != "Object"
        ) {
            applyForClass(curClazz)
            curClazz = curClazz.superclass
        }

        return element.hookFinished
    }

    private fun invokeExactCompat(
        clazz: String,
        methodName: String,
        original: MethodHandle,
        frame: EmulatedStackFrame,
        value: ReturnValue,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val element = findHookElement(clazz, methodName)!!

            ArtMethodUtils.setExecutableEntryPoint(
                element.method!!,
                element.memoryAddresses?.second!!
            )

            val thisObject = frame.getArgument(0)
            val args = frame.dumpArgs(true)

            value.result = (element.method as Method).invoke(thisObject, *args)

            ArtMethodUtils.setExecutableEntryPoint(
                element.method!!,
                element.memoryAddresses?.first!!
            )
        } else {
            Transformers.invokeExactNoChecks(original, frame)
        }
    }

    private fun findHookElement(clazz: String, methodName: String) =
        hooks[clazz]?.firstOrNull { it.methodName == methodName }

    /**
     * The overload whose parameter list [matches], searched up the superclass
     * chain. Selecting by shape rather than by arity is what keeps a hook
     * working across releases that add or retype a parameter.
     */
    fun findExecutable(
        clazz: String,
        methodName: String,
        loader: ClassLoader? = SystemServerHook.classLoader,
        matches: (List<Class<*>>) -> Boolean,
    ): Executable? {
        var curClazz: Class<*>? = try {
            Class.forName(clazz, true, loader)
        } catch (ex: ClassNotFoundException) {
            logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
            return null
        }

        while (curClazz != null) {
            val found = Reflection.getHiddenExecutables(curClazz).filter {
                it.name == methodName && matches(it.parameterTypes.toList())
            }.minByOrNull { it.parameterCount }

            if (found != null) {
                logD(ZygoteEntry.TAG) { "Selected overload: $found" }
                return found
            }

            curClazz = curClazz.superclass
        }

        logI(ZygoteEntry.TAG) { "No matching overload: $clazz -> $methodName" }

        return null
    }

    fun findParamCount(
        clazz: String,
        methodName: String,
        loader: ClassLoader? = SystemServerHook.classLoader,
        matches: (List<Class<*>>) -> Boolean,
    ) = findExecutable(clazz, methodName, loader, matches)?.parameterCount
        ?: PARAMETER_COUNT_UNKNOWN

    fun findAltMethod(
        clazzNames: List<String>,
        methodNames: List<String>,
        paramCount: Int = -1,
        loader: ClassLoader? = SystemServerHook.classLoader,
    ): Executable? {
        for (clazz in clazzNames) {
            var curClazz: Class<*>?
            try {
                curClazz = Class.forName(clazz, true, loader)
            } catch (ex: ClassNotFoundException) {
                logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
                continue
            }

            fun findMethods(clazz: Class<*>): List<Executable> {
                return Reflection.getHiddenExecutables(clazz).filter { executable ->
                    if (executable.name in methodNames) {
                        if (paramCount >= 0) {
                            return@filter paramCount == executable.parameterCount
                        }

                        return@filter true
                    }

                    return@filter false
                }.sortedWith { v1, v2 ->
                    v1.parameterCount.compareTo(v2.parameterCount)
                }
            }

            var methods = listOf<Executable>()

            while (
                methods.isEmpty() &&
                curClazz != null &&
                curClazz.javaClass.simpleName != "Object"
            ) {
                methods = findMethods(curClazz)
                curClazz = curClazz.superclass
            }

            return methods.firstOrNull()
        }

        logI(ZygoteEntry.TAG) { "Invalid hook detected: $clazzNames -> $methodNames($paramCount)" }

        return null
    }
}
