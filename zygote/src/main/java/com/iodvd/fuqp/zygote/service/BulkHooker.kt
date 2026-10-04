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
import com.iodvd.fuqp.zygote.service.UserService.service
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logE
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logV
import com.iodvd.fuqp.zygote.util.ServiceUtils
import com.iodvd.fuqp.zygote.util.ZLUtils.dumpArgs
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZLUtils.setReturnValue
import com.iodvd.fuqp.zygote.util.ZygoteConstants.CONSTRUCTOR_METHOD_NAME
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class BulkHooker {
    internal companion object {
        const val PARAMETER_COUNT_UNKNOWN = -1
    }

    var hooksWasCrashed = false

    internal val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<HookElement>>()

    internal fun isHookAvailable(clazz: String, method: String) = findHookElement(clazz, method) != null

    private fun findHookElement(clazz: String, method: String) =
        hooks[clazz]?.firstOrNull { it.methodName == method }

    /** True when the hook is installed; false when it is disabled or unbindable. */
    private fun addHook(
        clazz: String,
        methodName: String,
        argumentCount: Int,
        paramTypes: List<Class<*>>?,
        impl: HookTransformer,
    ): Boolean {
        val isConstructorHook = methodName == CONSTRUCTOR_METHOD_NAME
        if (isConstructorHook && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            logI(ZygoteEntry.TAG) { "Constructor hook removed for Android 12-: $clazz -> $methodName($argumentCount)" }
        }

        val inDisabledHooks = service?.config?.disabledHooks?.any {
            clazz == it.className &&
                    methodName == it.methodName &&
                    argumentCount == it.argumentCount
        }

        if (inDisabledHooks == true) {
            logI(ZygoteEntry.TAG) { "Disabled hook: $clazz -> $methodName($argumentCount)" }
            return false
        }

        val element = HookElement(
            impl = impl,
            methodName = methodName,
            argumentCount = argumentCount,
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

        if (!hooksWasCrashed) {
            logI(ZygoteEntry.TAG) { "Invalid hook removed: $clazz -> $methodName($argumentCount)" }
        }
        return false
    }

    internal fun hookBefore(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount, paramTypes) { original, frame ->
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
     * Brackets one invocation: [before] runs ahead of the original, and [after] runs once it
     * has returned or thrown - always, on the same thread - holding the original's result
     * exactly as [hookAfter] would see it. [after] runs even when the original threw (check
     * `returnValue.throwable`), because teardown must not depend on the call succeeding.
     *
     * Registering a [hookBefore] and a [hookAfter] on one method would mean two transformers on
     * the same target, so this is the only way to bracket a single invocation. That makes it
     * safe to park per-call state in a ThreadLocal from [before] - binder threads are pooled, so
     * state that outlived its call would be read by an unrelated later query on the same thread.
     */
    internal fun hookAround(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        before: (methodName: String, frame: EmulatedStackFrame) -> Unit,
        after: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount, paramTypes) { original, frame ->
        val value = ReturnValue()

        try {
            before(methodName, frame)
        } catch (it: Throwable) {
            logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
        }

        try {
            try {
                invokeExactCompat(clazz, methodName, original, frame, value)
            } catch (it: Throwable) {
                logD(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on original function" }
                value.throwable = it
            }

            if (value.throwable == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                value.result = frame.accessor().getValue(RETURN_VALUE_IDX)
            }
        } finally {
            try {
                after(methodName, frame, value)
            } catch (it: Throwable) {
                logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
            }
        }

        value.throwable?.let {
            ServiceUtils.clearStackTraces(it)

            throw it
        }

        frame.setReturnValue(value.result)
    }

    internal fun hookAfter(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        handleAfterThrows: Boolean = false,
        hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount, paramTypes) { original, frame ->
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

        if (handleAfterThrows || value.throwable == null) {
            try {
                hook(methodName, frame, value)
            } catch (it: Throwable) {
                logE(ZygoteEntry.TAG, it) { it.message ?: "Unknown error on hook" }
            }
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
        // do not apply next hooks when the previous one was crashed
        if (hooksWasCrashed) {
            return false
        }

        var curClazz = try {
            Class.forName(clazz, true, loader)
        } catch (ex: ClassNotFoundException) {
            logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
            return false
        }

        while (
            !element.hookFinished &&
            curClazz != null &&
            curClazz.javaClass.simpleName != "Object"
        ) {
            resolveExecutable(curClazz, element.methodName, element.argumentCount, element.paramTypes)?.let { executable ->
                logD(ZygoteEntry.TAG) { "Hooked constructor: $executable" }

                val memoryAddresses = try {
                    Hooks.hook(
                        executable, Hooks.EntryPointType.DIRECT,
                        element.impl, Hooks.EntryPointType.DIRECT
                    )
                } catch (e: Throwable) {
                    logE(ZygoteEntry.TAG, e) {
                        "Hook $clazz -> ${element.methodName}(${element.argumentCount}) crashed!"
                    }

                    hooksWasCrashed = true

                    return false
                }

                logV(ZygoteEntry.TAG) { "Memory address map: $memoryAddresses" }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    element.memoryAddresses = memoryAddresses
                    element.method = executable
                }

                element.hookFinished = true
            }

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

            // TODO: DO NOT USE ... as Constructor<*>, IT BREAKS TANGO!!!
            value.result = (element.method as Method).invoke(thisObject, *args)

            ArtMethodUtils.setExecutableEntryPoint(
                element.method!!,
                element.memoryAddresses?.first!!
            )
        } else {
            Transformers.invokeExactNoChecks(original, frame)
        }
    }

    fun findAltMethod(
        clazzNames: List<String>,
        methodNames: List<String>,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        loader: ClassLoader? = SystemServerHook.classLoader,
    ): Executable? {
        for (clazz in clazzNames) {
            var curClazz = try {
                Class.forName(clazz, true, loader)
            } catch (ex: ClassNotFoundException) {
                logE(ZygoteEntry.TAG, ex) { "Class $clazz not found" }
                continue
            }

            var methods = listOf<Executable>()
            while (
                methods.isEmpty() &&
                curClazz != null &&
                curClazz.javaClass.simpleName != "Object"
            ) {
                methods = methodNames.mapNotNull {
                    resolveExecutable(curClazz, it, argumentCount)
                }
                curClazz = curClazz.superclass
            }

            return methods.firstOrNull() ?: continue
        }

        return null
    }

    private fun resolveExecutable(
        clazz: Class<*>?,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
    ): Executable? {
        val isConstructorHook = methodName == CONSTRUCTOR_METHOD_NAME

        return if (isConstructorHook) {
            Reflection.getHiddenConstructors(clazz).let { constructors ->
                if (argumentCount >= 0) {
                    constructors.filter {
                        argumentCount == it.parameterCount
                    }.toTypedArray()
                } else {
                    constructors
                }
            }.firstOrNull()
        } else {
            Reflection.getHiddenExecutables(clazz).filter { executable ->
                if (methodName == executable.name) {
                    paramTypes?.let {
                        return@filter it == executable.parameterTypes.toList()
                    }

                    if (argumentCount >= 0) {
                        return@filter argumentCount == executable.parameterCount
                    }

                    return@filter true
                }

                return@filter false
            }.firstOrNull()
        }
    }

    fun findParamCount(
        clazz: String,
        methodName: String,
        loader: ClassLoader? = SystemServerHook.classLoader,
        matches: (List<Class<*>>) -> Boolean,
    ) = findExecutable(clazz, methodName, loader, matches)?.parameterCount
        ?: PARAMETER_COUNT_UNKNOWN

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
}
