package com.iodvd.fuqp.zygote.hook

import android.annotation.SuppressLint
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import com.iodvd.fuqp.zygote.util.Logcat.logD
import com.iodvd.fuqp.zygote.util.Logcat.logE
import com.iodvd.fuqp.zygote.util.Logcat.logI
import com.iodvd.fuqp.zygote.util.Logcat.logW
import com.iodvd.fuqp.zygote.util.ServiceUtils
import com.iodvd.fuqp.zygote.util.ZLUtils.getArgument
import com.iodvd.fuqp.zygote.util.ZLUtils.setArgument
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * Hides an active VPN from apps with `hideVpn` on, while their traffic keeps using it
 * ("mode B", docs/VPN_HIDING.md): every network handle and description those apps get is the
 * VPN's underlying network instead.
 *
 * ConnectivityService ships in a standalone system-server jar (jarjar-renamed on API 37), so
 * it has its own classloader and starts after the rest of FUQP's hooks are in. [load] waits for
 * the `connectivity` binder - in this process that is the service object itself - and hooks
 * through its class. Everything is resolved up front and nothing is hooked unless all of it
 * resolves: half the hooks would hand the app contradictory answers, which is a detection
 * signal of its own.
 */
class VpnHook : IFrameworkHook {
    override val TAG = "VpnHook"

    private companion object {
        const val CONNECTIVITY_SERVICE = "connectivity"

        // android.net constants, read from the API 37 framework-connectivity dex.
        const val TRANSPORT_VPN = 4
        const val NET_CAPABILITY_NOT_VPN = 15
        const val TYPE_VPN = 17
        const val CALLBACK_LOST = 4
        const val REQUEST_TYPE_LISTEN = "LISTEN"
    }

    // Runs in system_server, which is exempt from the non-SDK interface restrictions these flag.
    @SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private class Members(cs: Class<*>, loader: ClassLoader?) {
        val nai: Class<*> = Class.forName("${cs.name.substringBeforeLast('.')}.connectivity.NetworkAgentInfo", false, loader)
        val nri: Class<*> = Class.forName("${cs.name}\$NetworkRequestInfo", false, loader)

        val naiIsVpn: Method = nai.getDeclaredMethod("isVPN").accessible()
        val naiNetwork: Field = nai.getDeclaredField("network").accessible()
        val naiUnderlying: Field = nai.getDeclaredField("declaredUnderlyingNetworks").accessible()
        val nriUid: Field = nri.getDeclaredField("mUid").accessible()
        val nriRequest: Method = nri.getDeclaredMethod("getNetworkRequestForCallback").accessible()
        val csNaiForNetwork: Method = cs.getDeclaredMethod("getNetworkAgentInfoForNetwork", Network::class.java).accessible()
        val csDefaultNai: Method = cs.getDeclaredMethod("getDefaultNetwork").accessible()
        val requestType: Field = Class.forName("android.net.NetworkRequest").getDeclaredField("type").accessible()

        val ncRemoveTransport: Method = NetworkCapabilities::class.java.getDeclaredMethod("removeTransportType", Int::class.java).accessible()
        val ncAddCapability: Method = NetworkCapabilities::class.java.getDeclaredMethod("addCapability", Int::class.java).accessible()
        val ncSetTransportInfo: Method = NetworkCapabilities::class.java.getDeclaredMethod(
            "setTransportInfo", Class.forName("android.net.TransportInfo"),
        ).accessible()
        val ncSetUnderlying: Method = NetworkCapabilities::class.java.getDeclaredMethod("setUnderlyingNetworks", List::class.java).accessible()
        val ncSetOwnerUid: Method = NetworkCapabilities::class.java.getDeclaredMethod("setOwnerUid", Int::class.java).accessible()
        val ncSetAdminUids: Method = NetworkCapabilities::class.java.getDeclaredMethod("setAdministratorUids", IntArray::class.java).accessible()

        // Methods we hook, checked here so a missing one aborts before anything is installed.
        init {
            cs.getDeclaredMethod("getActiveNetworkForUidInternal", Int::class.java, Boolean::class.java)
            cs.getDeclaredMethod("getNetworkAgentInfoForUid", Int::class.java)
            cs.getDeclaredMethod("getAllNetworks")
            cs.getDeclaredMethod("getNetworkForType", Int::class.java)
            cs.getDeclaredMethod("getNetworkInfo", Int::class.java)
            cs.getDeclaredMethod("getLinkPropertiesForType", Int::class.java)
            cs.getDeclaredMethod("getNetworkCapabilities", Network::class.java, String::class.java, String::class.java)
            cs.getDeclaredMethod("getLinkProperties", Network::class.java)
            cs.getDeclaredMethod(
                "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled",
                NetworkCapabilities::class.java, Boolean::class.java, Int::class.java, Int::class.java,
                String::class.java, String::class.java,
            )
            cs.getDeclaredMethod("callCallbackForRequest", nri, nai, Int::class.java, Int::class.java)
        }

        private fun <T : java.lang.reflect.AccessibleObject> T.accessible() = apply { isAccessible = true }
    }

    override fun load() {
        logI(TAG) { "Load hook" }

        thread(name = "FUQP-VpnHook", isDaemon = true) {
            try {
                val cs = ServiceUtils.waitForService(CONNECTIVITY_SERVICE)
                if (cs == null || !cs.javaClass.name.endsWith(".ConnectivityService")) {
                    logW(TAG) { "connectivity service not found (${cs?.javaClass?.name}), VPN hiding disabled" }
                    return@thread
                }
                install(cs, Members(cs.javaClass, cs.javaClass.classLoader))
            } catch (cause: Throwable) {
                logE(TAG, cause) { "VPN hiding disabled: connectivity internals did not resolve" }
            }
        }
    }

    private fun install(cs: Any, m: Members) {
        val clazz = cs.javaClass.name
        val loader = cs.javaClass.classLoader

        fun isVpn(nai: Any?) = nai != null && m.naiIsVpn.invoke(nai) == true
        fun naiOf(network: Network?) = network?.let { m.csNaiForNetwork.invoke(cs, it) }
        fun isVpnNetwork(network: Network?) = isVpn(naiOf(network))

        // What a hiding app sees in place of [vpnNai]: its first declared underlying network,
        // else the system default (which is never the VPN). Null if neither is usable.
        fun underlyingNai(vpnNai: Any): Any? {
            val declared = (m.naiUnderlying.get(vpnNai) as Array<*>?)?.firstOrNull() as Network?
            return (naiOf(declared) ?: m.csDefaultNai.invoke(cs))?.takeIf { !isVpn(it) }
        }

        fun hiddenForCaller() = service.isVpnHiddenFor(Binder.getCallingUid())

        hooker.apply {
            var bound = 0
            fun count(ok: Boolean) { if (ok) bound++ }

            // getActiveNetwork / getActiveNetworkForUid / getProxyForNetwork(null) all resolve here.
            // Not the (NetworkAgentInfo, int, boolean) overload below it: that one also serves
            // updateDisallowedUidsForNetwork, which is enforcement, not an answer to the app.
            count(hookAfter(
                clazz, "getActiveNetworkForUidInternal",
                paramTypes = listOf(Int::class.java, Boolean::class.java), loader = loader,
            ) { _, frame, returnValue ->
                if (!service.isVpnHiddenFor(frame.getArgument(1) as Int)) return@hookAfter
                val nai = naiOf(returnValue.result as Network?)
                if (isVpn(nai)) returnValue.result = underlyingNai(nai!!)?.let { m.naiNetwork.get(it) }
            })

            // NetworkInfo and getActiveLinkProperties resolve here. AOSP already substitutes the
            // underlying network, but only when the VPN declared one; this covers the rest.
            count(hookAfter(clazz, "getNetworkAgentInfoForUid", paramTypes = listOf(Int::class.java), loader = loader) { _, frame, returnValue ->
                if (!service.isVpnHiddenFor(frame.getArgument(1) as Int)) return@hookAfter
                val nai = returnValue.result
                if (isVpn(nai)) returnValue.result = underlyingNai(nai!!)
            })

            count(hookAfter(clazz, "getAllNetworks", paramTypes = emptyList(), loader = loader) { _, _, returnValue ->
                if (!hiddenForCaller()) return@hookAfter
                val all = returnValue.result as Array<*>? ?: return@hookAfter
                returnValue.result = all.filterIsInstance<Network>().filterNot(::isVpnNetwork).toTypedArray()
            })

            count(hookAfter(clazz, "getNetworkForType", paramTypes = listOf(Int::class.java), loader = loader) { _, frame, returnValue ->
                if (!hiddenForCaller()) return@hookAfter
                if (frame.getArgument(1) == TYPE_VPN || isVpnNetwork(returnValue.result as Network?)) {
                    returnValue.result = null
                }
            })

            // Also what getAllNetworkInfo loops over.
            count(hookAfter(clazz, "getNetworkInfo", paramTypes = listOf(Int::class.java), loader = loader) { _, _, returnValue ->
                if (!hiddenForCaller()) return@hookAfter
                if ((returnValue.result as NetworkInfo?)?.type == TYPE_VPN) returnValue.result = null
            })

            count(hookAfter(clazz, "getLinkPropertiesForType", paramTypes = listOf(Int::class.java), loader = loader) { _, frame, returnValue ->
                if (hiddenForCaller() && frame.getArgument(1) == TYPE_VPN) returnValue.result = null
            })

            // A VPN handle should never reach a hiding app (see above and the callbacks below);
            // if one does, it describes a network that does not exist.
            count(hookAfter(
                clazz, "getNetworkCapabilities",
                paramTypes = listOf(Network::class.java, String::class.java, String::class.java), loader = loader,
            ) { _, frame, returnValue ->
                if (hiddenForCaller() && isVpnNetwork(frame.getArgument(1) as Network?)) returnValue.result = null
            })

            count(hookAfter(clazz, "getLinkProperties", paramTypes = listOf(Network::class.java), loader = loader) { _, frame, returnValue ->
                if (hiddenForCaller() && isVpnNetwork(frame.getArgument(1) as Network?)) returnValue.result = null
            })

            // Every NetworkCapabilities handed to an app (getters and callbacks) is sanitised
            // here, with the recipient's uid. Safety net: strip VPN markers from anything that
            // still carries them. The original returns a fresh copy, so mutating it is safe.
            count(hookAfter(
                clazz, "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled",
                paramTypes = listOf(
                    NetworkCapabilities::class.java, Boolean::class.java, Int::class.java, Int::class.java,
                    String::class.java, String::class.java,
                ),
                loader = loader,
            ) { _, frame, returnValue ->
                if (!service.isVpnHiddenFor(frame.getArgument(4) as Int)) return@hookAfter
                val nc = returnValue.result as NetworkCapabilities? ?: return@hookAfter
                if (!nc.hasTransport(TRANSPORT_VPN)) return@hookAfter
                m.ncRemoveTransport.invoke(nc, TRANSPORT_VPN)
                m.ncAddCapability.invoke(nc, NET_CAPABILITY_NOT_VPN)
                m.ncSetTransportInfo.invoke(nc, null)
                m.ncSetUnderlying.invoke(nc, null)
                m.ncSetOwnerUid.invoke(nc, -1)
                m.ncSetAdminUids.invoke(nc, IntArray(0))
                returnValue.result = nc
            })

            // Callbacks are pushed to the app, so the recipient comes from the request record.
            // A listen request is told about every matching network, the underlying one
            // included, so its VPN callbacks are simply dropped. Every other kind (default
            // tracking, requestNetwork, best-matching) is told about one network only, which here
            // is the VPN: those get the underlying network instead, or the app would wait for a
            // network forever. A VPN loss is never passed on: the underlying network is still up.
            count(hookBefore(
                clazz, "callCallbackForRequest",
                paramTypes = listOf(m.nri, m.nai, Int::class.java, Int::class.java), loader = loader,
            ) { _, frame, returnValue ->
                val nai = frame.getArgument(2) ?: return@hookBefore
                val nri = frame.getArgument(1) ?: return@hookBefore
                if (!isVpn(nai) || !service.isVpnHiddenFor(m.nriUid.getInt(nri))) return@hookBefore

                val request = m.nriRequest.invoke(nri)
                val isListen = request != null &&
                        (m.requestType.get(request) as Enum<*>?)?.name == REQUEST_TYPE_LISTEN
                val replacement = if (!isListen && frame.getArgument(3) != CALLBACK_LOST) underlyingNai(nai) else null

                if (replacement != null) {
                    frame.setArgument(2, replacement)
                } else {
                    returnValue.result = null // skip the original: this callback is not sent
                }
                logD(TAG) { "@callCallbackForRequest: ${if (replacement != null) "rewrote" else "dropped"} VPN callback ${frame.getArgument(3)}" }
            })

            logI(TAG) { "VPN hiding hooks bound: $bound/10" }
        }
    }
}
