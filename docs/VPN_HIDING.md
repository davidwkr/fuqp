# Hiding an active VPN from chosen apps ("mode B")

Design notes for a per-app option that makes an active VPN invisible to a querying app
while that app's traffic **keeps going through the VPN**.

**Status: implemented in `zygote/.../hook/VpnHook.kt`, not yet verified on a device with a
VPN up.** Hook points below were confirmed against the API 37 (Android 17) apollo build with
`tools/vpn-hiding/probe.sh` and a `dexdump -d` read of ConnectivityService on 2026-11-02.
Other API levels are unverified: the hook resolves every member up front and installs nothing
if any is missing.

## Two modes, and why this one is the hard one

- **Mode A, exclude from VPN.** Route the app outside the VPN, like per-app exclusion in the
  VPN client. Android then reports "no VPN" to it consistently by itself. Cost: the app's
  traffic leaves over the real connection. Not this document.
- **Mode B, mask VPN (this document).** Traffic stays in the VPN; FUQP rewrites what the
  connectivity APIs tell the app. Every API path that is missed becomes a *mismatch*
  (for example a default network whose id the app cannot look up), which is itself a
  detection signal. Completeness and internal consistency matter more than coverage of any
  single call.

## Invariant

For a caller with the option on, **the VPN network does not exist**:

1. No `Network` handle it receives refers to a VPN network. Wherever the VPN would appear
   (its default network, `getAllNetworks`, callbacks), it sees the VPN's underlying
   network instead.
2. Anything that describes a network (`NetworkCapabilities`, `LinkProperties`,
   `NetworkInfo`) describes that underlying network, with no VPN transport, no
   `VpnTransportInfo`, `NOT_VPN` present, no `tun*` interface.
3. Answers are consistent across paths: the network from `getActiveNetwork()` must be in
   `getAllNetworks()`, its capabilities must match what the default-network callback
   delivered, and so on.

If the VPN has no usable underlying network, nothing sensible can be shown. Fall back to
leaving the answer unchanged rather than inventing a network.

## Where it is done

FUQP runs in `system_server`, which hosts ConnectivityService.

- **Classloader.** On API 37 `service-connectivity.jar` is on `STANDALONE_SYSTEMSERVER_JARS`,
  not `SYSTEMSERVERCLASSPATH`, so `SystemServerHook.classLoader` cannot see it. The class is
  also jarjar-renamed: `android.net.connectivity.com.android.server.ConnectivityService`.
- **Timing.** ConnectivityService starts after FUQP installs its hooks. `VpnHook` waits for the
  `connectivity` binder on its own thread; in this process that binder is the service object,
  so its class gives both the real class name and the classloader. `BulkHooker.addHook`,
  `hookBefore` and `hookAfter` take a `loader` for this.
- **Who is hiding.** `AppConfig.hideVpn`, resolved to an app-id set in
  `FUQPService.refreshDerivedFlags`, so every hook bails in O(1) for other uids.

## Hooks (verified on API 37)

"Underlying" means the VPN's first `declaredUnderlyingNetworks` entry, else
`ConnectivityService.getDefaultNetwork()` (the system default, never the VPN).

| App-visible API | Hooked method | Uid from | Treatment |
|---|---|---|---|
| `getActiveNetwork()`, `getActiveNetworkForUid`, `getProxyForNetwork(null)` | `getActiveNetworkForUidInternal(int, boolean)` | arg | VPN → underlying network |
| `getActiveNetworkInfo()`, `getNetworkInfo(type)`, `getActiveLinkProperties()` | `getNetworkAgentInfoForUid(int)` | arg | VPN → underlying. AOSP already does this when the VPN declared underlying networks; this covers VPNs that do not. |
| `getAllNetworks()` | same name | `Binder.getCallingUid()` | VPN networks removed. AOSP does no per-caller filtering here. |
| `getNetworkForType(TYPE_VPN)` | same name | calling uid | `null` |
| `getNetworkInfo(TYPE_VPN)`, and so `getAllNetworkInfo()` | `getNetworkInfo(int)` | calling uid | `null` for a VPN result |
| `getLinkProperties(TYPE_VPN)` | `getLinkPropertiesForType(int)` | calling uid | `null` |
| `getNetworkCapabilities(vpn)` | `getNetworkCapabilities(Network, String, String)` | calling uid | `null` |
| `getLinkProperties(vpn)` | `getLinkProperties(Network)` | calling uid | `null` |
| every NetworkCapabilities sent to an app (getters and callbacks) | `createWithSensitiveInfoSanitizedIfNecessaryWhenParceled(nc, Z, pid, uid, pkg, tag)` | arg 3 | Safety net: drop `TRANSPORT_VPN`, add `NOT_VPN`, clear transport info, underlying networks, owner and admin uids |
| network callbacks | `callCallbackForRequest(NetworkRequestInfo, NetworkAgentInfo, int, int)` | `NetworkRequestInfo.mUid` | see below |

Deliberately **not** hooked: `getActiveNetworkForUidInternal(NetworkAgentInfo, int, boolean)`.
Besides the getters it serves `updateDisallowedUidsForNetwork`, which is enforcement; changing
its answer could change real routing or firewall state for the uid.

### Callbacks

Callbacks are sent from the handler thread through the request's `Messenger`, so the recipient
comes from `NetworkRequestInfo.mUid`, not the binder caller. For a VPN `NetworkAgentInfo`:

- `LISTEN` requests (`registerNetworkCallback`) are told about every matching network,
  including the underlying one, so VPN callbacks to them are dropped.
- Every other type (`TRACK_DEFAULT`, `REQUEST`, `LISTEN_FOR_BEST`, …) is told about one network,
  which is the VPN. The `NetworkAgentInfo` argument is swapped for the underlying one, so the
  bundle carries its network, capabilities and link properties. Dropping these instead would
  leave a `requestNetwork` caller waiting forever.
- `CALLBACK_LOST` (4) for the VPN is always dropped: the underlying network is still up.

Known imperfection: when the VPN comes up, a default-network callback gets `onAvailable` for
the underlying network it already has, and if the underlying network changes while the VPN
stays up, the app is told through capability/link updates rather than a fresh `onAvailable`.

## Out of scope: things only the app's own process can see

These are read without going through `system_server`, so a system_server-only FUQP
**cannot** hide them:

- `java.net.NetworkInterface` / `getifaddrs`: the `tun0` interface and its address.
- `/proc/net/route`, `/proc/net/if_inet6`, `/sys/class/net/*`.
- Interface-name traffic counters.
- Server-side checks: VPN or datacenter exit IP, DNS resolver location.

A detector that uses any of these will still see the VPN. Mode B lowers the signal; it does
not remove it.

## Config and UI

`JsonConfig.AppConfig.hideVpn: Boolean = false`, the "Hide VPN" switch on the app's Spoofing
screen. Its summary states the limits: traffic still uses the VPN; interface-level and
server-side checks can still detect it.

## Test plan

1. Logcat/FUQP log after boot: `VPN hiding hooks bound: 10/10`.
2. Baseline with a test VPN up and the option off: record what VD Infos' Network section and
   the real detector apps report.
3. Option on: every row in (2) should flip to "no VPN", with VD Infos' own consistency
   checks reporting no mismatch.
4. Regression: the VPN client itself, a non-opted-in app (must still see the VPN), Play
   Services connectivity, captive-portal detection, and FUQP package hiding.
5. Toggle the option and change networks (Wi-Fi ↔ mobile) while the app is running: the
   callbacks must follow the underlying network without exposing the VPN.
