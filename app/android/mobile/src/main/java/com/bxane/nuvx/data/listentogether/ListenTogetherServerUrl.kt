package com.bxane.nuvx.data.listentogether

import java.net.URI

internal object ListenTogetherServerUrl {
    fun parseAndNormalizeServerUrl(raw: String): ServerUrlValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ServerUrlValidationResult.Valid("")
        if (trimmed.any { it.isWhitespace() }) return ServerUrlValidationResult.Invalid(ServerUrlError.Whitespace)

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
            if (authority.isEmpty()) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            }
            val portStr = authority.substringAfterLast(':', "")
            if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) {
                val portNum = portStr.toLongOrNull()
                if (portNum != null && portNum !in 1..65535) {
                    return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
                }
            }
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
        }

        val scheme = uri.scheme?.lowercase() ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
        if (scheme != "http" && scheme != "https") {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
        }

        if (!uri.rawQuery.isNullOrEmpty() || candidate.contains("?")) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.HasQuery)
        }

        if (!uri.rawFragment.isNullOrEmpty() || candidate.contains("#")) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.HasFragment)
        }

        val pathSegments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        for (segment in pathSegments) {
            if (segment == "." || segment == "..") {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPath)
            }
        }
        val canonicalPath = if (pathSegments.isEmpty()) "" else "/" + pathSegments.joinToString("/")

        val port = uri.port
        if (port != -1 && port !in 1..65535) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
        }
        val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        val portStr = authority.substringAfterLast(':', "")
        if (portStr.isNotEmpty() && portStr.all { it.isDigit() } && authority.contains(":")) {
            val portNum = portStr.toLongOrNull()
            if (portNum != null && portNum !in 1..65535) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
            }
        }

        val rawHost = uri.host ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
        if (rawHost.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

        val canonicalHost: String = when {
            rawHost.startsWith("[") && rawHost.endsWith("]") -> {
                val unbracketed = rawHost.substring(1, rawHost.length - 1)
                if (!unbracketed.contains(":")) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                "[${unbracketed.lowercase()}]"
            }
            rawHost.contains(":") -> {
                "[${rawHost.lowercase()}]"
            }
            rawHost.equals("localhost", ignoreCase = true) -> {
                "localhost"
            }
            else -> {
                if (rawHost.length > 253) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                if (rawHost.startsWith(".") || rawHost.endsWith(".")) {
                    return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                }
                val labels = rawHost.split('.')
                if (labels.size < 2) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                val labelRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")
                for (label in labels) {
                    if (label.isEmpty() || label.length > 63 || !label.matches(labelRegex)) {
                        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                    }
                }
                rawHost.lowercase()
            }
        }

        val portSuffix = if (port != -1) ":$port" else ""
        return ServerUrlValidationResult.Valid("${scheme.lowercase()}://$canonicalHost$portSuffix$canonicalPath")
    }
}
