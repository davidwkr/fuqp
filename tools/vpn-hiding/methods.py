#!/usr/bin/env python3
"""List the VPN-relevant methods (name + descriptor) from a plain `dexdump` listing.

Usage: methods.py <dexdump.txt>
The candidate names are hypotheses from memory of AOSP; whatever this prints is the ground
truth for the device it was pulled from. See docs/VPN_HIDING.md.
"""
import re
import sys

CLASSES = {
    "Lcom/android/server/ConnectivityService;": re.compile(
        r"^(getActiveNetwork\w*|getActiveLinkProperties|getAllNetwork\w*|getNetworkInfo\w*"
        r"|getNetworkCapabilities|getLinkProperties\w*|getDefaultNetworkCapabilitiesForUser"
        r"|getNetworkForType|getProxyForNetwork|isActiveNetworkMetered"
        r"|createWithLocationInfoSanitizedIfNecessaryWhenParceled"
        r"|networkCapabilitiesRestrictedForCallerPermissions"
        r"|linkPropertiesRestrictedForCallerPermissions|callCallbackForRequest"
        r"|putParcelable|getFilteredNetworkInfo\w*|filterNetworkInfo|getUnfilteredActiveNetworkState"
        r"|getVpnUnderlyingNetworks|getDefaultNetworkForUid\w*)$"
    ),
    "Landroid/net/NetworkCapabilities;": re.compile(
        r"^(hasTransport|getTransportTypes|hasCapability|getTransportInfo|getUnderlyingNetworks"
        r"|writeToParcel|removeTransportType|addCapability|setTransportInfo|setUnderlyingNetworks"
        r"|setOwnerUid|setAdministratorUids|setUids)$"
    ),
    "Landroid/net/LinkProperties;": re.compile(r"^(getInterfaceName|writeToParcel|setInterfaceName)$"),
    "Landroid/net/NetworkInfo;": re.compile(r"^(getType|getTypeName|writeToParcel)$"),
}

# The Tethering module jarjars its service classes, e.g.
# Landroid/net/connectivity/com/android/server/ConnectivityService; on API 37.
JARJAR_PREFIX = "Landroid/net/connectivity/"

cls = None
in_methods = False
name = None
found = {}
for line in open(sys.argv[1], errors="replace"):
    m = re.match(r"\s*Class descriptor\s*:\s*'(.+)'", line)
    if m:
        cls = m.group(1).replace(JARJAR_PREFIX, "L", 1)
        in_methods = False  # field listings come first; don't read them as methods
        continue
    if re.match(r"\s*(Direct|Virtual) methods", line):
        in_methods = True
        continue
    if not in_methods or cls not in CLASSES:
        continue
    m = re.match(r"\s*name\s*:\s*'(.+)'", line)
    if m:
        name = m.group(1)
        continue
    m = re.match(r"\s*type\s*:\s*'(.+)'", line)
    if m and name and CLASSES[cls].match(name):
        found.setdefault(cls, set()).add(f"{name}{m.group(1)}")
        name = None

for c in CLASSES:
    print(c)
    for sig in sorted(found.get(c, ())):
        print(f"    {sig}")
    if c not in found:
        print("    (class not found in pulled jars)")
