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
import com.iodvd.fuqp.zygote.util.ZygoteConstants.PARAMETER_COUNT_UNKNOWN
import java.lang.invoke.MethodHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class BulkHooker {
    var hooksWasCrashed = false

    internal val hooks = ConcurrentHashMap<String, CopyOnWriteArrayList<HookElement>>()

    internal fun isHookAvailable(clazz: String, method: String) = findHookElement(clazz, method) != null

    private fun findHookElement(clazz: String, method: String) =
        hooks[clazz]?.firstOrNull { it.methodName == method }

    // Deliberately not computeIfAbsent: it holds the bin lock across the mapping function and is
    // documented as non-reentrant. applyHook/hookExecutable have already armed the hook, so by the
    // time we record it a hooked method can fire on another thread and reach this same map.
    // putIfAbsent takes no user code under the lock, so it cannot wedge the bin; the loser of a
    // race just drops its unused list.
    private fun putHook(clazz: String, element: HookElement) {
        val existing = hooks[clazz]
            ?: CopyOnWriteArrayList<HookElement>().let { hooks.putIfAbsent(clazz, it) ?: it }
        existing.add(element)
    }

    /** True when the hook is installed; false when it is disabled or unbindable. */
    @PublishedApi
    internal fun addHook(
        clazz: String,
        methodName: String,
        argumentCount: Int,
        paramTypes: List<Class<*>>? = null,
        impl: HookTransformer,
    ): Boolean {
        val element = preAddHook(clazz, methodName, argumentCount, paramTypes, impl)

        if (element != null && applyHook(clazz, element)) {
            putHook(clazz, element)
            return true
        }

        if (!hooksWasCrashed) {
            logI(ZygoteEntry.TAG) { "Invalid hook removed: $clazz -> $methodName($argumentCount)" }
        }
        return false
    }

    /** True when the hook is installed; false when it is disabled or unbindable. */
    @PublishedApi
    internal fun addHookOnMethod(executable: Executable, impl: HookTransformer): Boolean {
        val methodName = executable.name
        val clazz = executable.declaringClass.name
        val argumentCount = executable.parameterCount

        val element = preAddHook(clazz, methodName, argumentCount, null, impl)

        if (element != null && hookExecutable(executable, element)) {
            putHook(clazz, element)
            return true
        }

        if (!hooksWasCrashed) {
            logI(ZygoteEntry.TAG) { "Invalid hook removed: $clazz -> $methodName($argumentCount)" }
        }
        return false
    }

    private fun preAddHook(
        clazz: String,
        methodName: String,
        argumentCount: Int,
        paramTypes: List<Class<*>>?,
        impl: HookTransformer,
    ): HookElement? {
        val isConstructorHook = methodName == CONSTRUCTOR_METHOD_NAME
        if (isConstructorHook && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            logI(ZygoteEntry.TAG) { "Constructor hook removed for Android 12-: $clazz -> $methodName($argumentCount)" }
            return null
        }

        val inDisabledHooks = service?.config?.disabledHooks?.any {
            clazz == it.className &&
                    methodName == it.methodName &&
                    argumentCount == it.argumentCount
        }

        if (inDisabledHooks == true) {
            logI(ZygoteEntry.TAG) { "Disabled hook: $clazz -> $methodName($argumentCount)" }
            return null
        }

        return HookElement(
            impl = impl,
            methodName = methodName,
            argumentCount = argumentCount,
            paramTypes = paramTypes,
        )
    }

    inline fun hookBefore(
        clazzNames: List<String>,
        methodNames: List<String>,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = findAltMethod(clazzNames, methodNames, argumentCount)?.let { executable ->
        addHookOnMethod(executable) { original, frame ->
            val methodName = executable.name
            val clazz = executable.declaringClass.name

            hookBeforeCommon(clazz, methodName, original, frame, hook)
        }
    }

    inline fun hookBefore(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount, paramTypes) { original, frame ->
        hookBeforeCommon(clazz, methodName, original, frame, hook)
    }

    @PublishedApi
    internal inline fun hookBeforeCommon(
        clazz: String,
        methodName: String,
        original: MethodHandle,
        frame: EmulatedStackFrame,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) {
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

    inline fun hookAfter(
        clazzNames: List<String>,
        methodNames: List<String>,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        handleAfterThrows: Boolean = false,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = findAltMethod(clazzNames, methodNames, argumentCount)?.let { executable ->
        addHookOnMethod(executable) { original, frame ->
            val methodName = executable.name
            val clazz = executable.declaringClass.name

            hookAfterCommon(clazz, methodName, handleAfterThrows, original, frame, hook)
        }
    }

    inline fun hookAfter(
        clazz: String,
        methodName: String,
        argumentCount: Int = PARAMETER_COUNT_UNKNOWN,
        paramTypes: List<Class<*>>? = null,
        handleAfterThrows: Boolean = false,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) = addHook(clazz, methodName, argumentCount, paramTypes) { original, frame ->
        hookAfterCommon(clazz, methodName, handleAfterThrows, original, frame, hook)
    }

    @PublishedApi
    internal inline fun hookAfterCommon(
        clazz: String,
        methodName: String,
        handleAfterThrows: Boolean = false,
        original: MethodHandle,
        frame: EmulatedStackFrame,
        crossinline hook: (methodName: String, frame: EmulatedStackFrame, returnValue: ReturnValue) -> Unit,
    ) {
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

    private fun applyHook(
        clazz: String,
        element: HookElement,
        loader: ClassLoader? = SystemServerHook.classLoader,
    ): Boolean {
        // do not apply next hooks when the previous hook was crashed
        if (hooksWasCrashed) return false

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
            val executable = resolveExecutable(curClazz, element.methodName, element.argumentCount, element.paramTypes)
            if (hookExecutable(executable, element)) return true

            curClazz = curClazz.superclass
        }

        return element.hookFinished
    }

    private fun hookExecutable(executable: Executable?, element: HookElement): Boolean {
        // do not apply next hooks when null object received or the previous hook was crashed
        if (executable == null || hooksWasCrashed) return false

        logD(ZygoteEntry.TAG) { "Hooked constructor: $executable" }

        val memoryAddresses = try {
            Hooks.hook(
                executable, Hooks.EntryPointType.DIRECT,
                element.impl, Hooks.EntryPointType.DIRECT
            )
        } catch (e: Throwable) {
            logE(ZygoteEntry.TAG, e) {
                "Hook ${executable.declaringClass} -> ${element.methodName}(${element.argumentCount}) crashed!"
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
        return true
    }

    @PublishedApi
    internal fun invokeExactCompat(
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

    @PublishedApi
    internal fun findAltMethod(
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

            while (
                curClazz != null &&
                curClazz.javaClass.simpleName != "Object"
            ) {
                val method = methodNames.firstNotNullOfOrNull {
                    resolveExecutable(curClazz, it, argumentCount)
                }
                if (method != null) return method

                curClazz = curClazz.superclass
            }
        }

        logD(ZygoteEntry.TAG) { "Cannot find $clazzNames -> $methodNames($argumentCount)" }

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
            Reflection.getHiddenConstructors(clazz).firstOrNull {
                return@firstOrNull argumentCount < 0 || argumentCount == it.parameterCount
            }
        } else {
            Reflection.getHiddenExecutables(clazz).firstOrNull { executable ->
                if (methodName != executable.name) return@firstOrNull false

                paramTypes?.let {
                    return@firstOrNull it == executable.parameterTypes.toList()
                }

                argumentCount < 0 || argumentCount == executable.parameterCount
            }
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
