package com.bxane.nuvx.data.listentogether


sealed interface ServerUrlError {

    data object Whitespace : ServerUrlError


    data object InvalidScheme : ServerUrlError


    data object InvalidHost : ServerUrlError


    data object InvalidPort : ServerUrlError


    data object InvalidPath : ServerUrlError


    data object HasQuery : ServerUrlError


    data object HasFragment : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

val ServerUrlValidationResult.normalizedOrNull: String?
    get() = (this as? ServerUrlValidationResult.Valid)?.normalizedUrl





































object ServerUrl {

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private val LABEL = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")

    fun parseAndNormalize(raw: String?): ServerUrlValidationResult {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ServerUrlValidationResult.Valid("")

        if (trimmed.any { it.isWhitespace() }) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.Whitespace)
        }




        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
        }

        val rest = withScheme.substringAfter("://")



        if ('?' in rest) return ServerUrlValidationResult.Invalid(ServerUrlError.HasQuery)
        if ('#' in rest) return ServerUrlValidationResult.Invalid(ServerUrlError.HasFragment)

        val authority = rest.substringBefore('/')
        val rawPath = rest.substringAfter('/', "")
        if (authority.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)



        val hostPort = authority.substringAfterLast('@')

        val (hostPart, portPart) = splitHostPort(hostPort)
        val port = when {
            portPart == null -> -1
            !portPart.all { it.isDigit() } || portPart.isEmpty() ->
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
            else -> portPart.toIntOrNull()
                ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
        }
        if (port != -1 && port !in 1..65535) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
        }

        val host = canonicalHost(hostPart) ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPath)
        }



        val path = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")







        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portSuffix = if (port == -1 || isDefaultPort) "" else ":$port"

        return ServerUrlValidationResult.Valid("$scheme://$host$portSuffix$path")
    }







    private fun splitHostPort(hostPort: String): Pair<String, String?> {
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return hostPort to null
            val host = hostPort.substring(0, close + 1)
            val after = hostPort.substring(close + 1)
            return host to after.takeIf { it.startsWith(":") }?.substring(1)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to null
        return hostPort.substring(0, colon) to hostPort.substring(colon + 1)
    }


    private fun canonicalHost(rawHost: String): String? {
        if (rawHost.isEmpty()) return null




        val unbracketed = rawHost.removePrefix("[").removeSuffix("]")
        if (unbracketed.contains(':')) {
            if (unbracketed.isEmpty()) return null
            return "[${unbracketed.lowercase()}]"
        }





        if (rawHost.equals("localhost", ignoreCase = true)) return "localhost"

        if (rawHost.length > MAX_HOST_LENGTH) return null


        if (rawHost.startsWith(".") || rawHost.endsWith(".")) return null

        val labels = rawHost.split('.')
        if (labels.size < 2) return null
        for (label in labels) {
            if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return null
            if (!label.matches(LABEL)) return null
        }
        return rawHost.lowercase()
    }
}
