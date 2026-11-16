package com.licht.nuvx.data.innertube.potoken

class PoTokenException(message: String) : Exception(message)

class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}
