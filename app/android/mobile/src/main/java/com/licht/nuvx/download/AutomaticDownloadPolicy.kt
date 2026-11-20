package com.licht.nuvx.download

/**
 * Background playlist downloads are opt-in. Wi-Fi-only means an actual unmetered Wi-Fi
 * connection, not merely any network that the OS happens to call unmetered.
 * Manual downloads keep their existing, separately configured policy.
 */
internal fun automaticDownloadAllowed(
    enabled: Boolean,
    wifiOnly: Boolean,
    connected: Boolean,
    isWifi: Boolean,
    isMetered: Boolean,
): Boolean = enabled && connected && (!wifiOnly || (isWifi && !isMetered))
