package com.bxane.nuvx.ui.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class TvAuthServerSocketTest {

    @Test
    fun clientsBeyondTheConnectionLimitAreClosed() {
        ServerSocket(0).use { server ->
            val clients = List(5) {
                Socket("127.0.0.1", server.localPort).apply { soTimeout = 1500 }
            }
            val accepted = List(clients.size) { server.accept() }
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                accepted.forEach { method.invoke(TvAuthServer, it, null) }

                assertEquals(-1, clients.last().getInputStream().read())
            } finally {
                clients.forEach { it.close() }
                accepted.forEach { it.close() }
            }
        }
    }

    @Test
    fun stalledRequestLineTimesOut() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort).apply { soTimeout = 7000 }
            val accepted = server.accept()
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                method.invoke(TvAuthServer, accepted, null)

                assertEquals(-1, client.getInputStream().read())
            } finally {
                client.close()
                accepted.close()
            }
        }
    }

    @Test
    fun stalledRequestBodyTimesOut() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort).apply { soTimeout = 7000 }
            val accepted = server.accept()
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                method.invoke(TvAuthServer, accepted, null)
                client.getOutputStream().write("POST /submit HTTP/1.1\r\nContent-Length: 4\r\n\r\n".toByteArray())
                client.getOutputStream().flush()

                assertEquals(-1, client.getInputStream().read())
            } finally {
                client.close()
                accepted.close()
            }
        }
    }

    @Test
    fun overlongRequestLineIsClosed() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort).apply { soTimeout = 1500 }
            val accepted = server.accept()
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                method.invoke(TvAuthServer, accepted, null)
                client.getOutputStream().write(ByteArray(8 * 1024 + 1) { 'A'.code.toByte() })
                client.getOutputStream().write('\n'.code)
                client.getOutputStream().flush()

                assertEquals(-1, client.getInputStream().read())
            } finally {
                client.close()
                accepted.close()
            }
        }
    }

    @Test
    fun oversizedHeadersAreRejected() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort).apply { soTimeout = 1500 }
            val accepted = server.accept()
            val method = TvAuthServer.javaClass.getDeclaredMethod("handleClient", Socket::class.java, Function1::class.java)
            method.isAccessible = true
            try {
                method.invoke(TvAuthServer, accepted, null)
                client.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())
                client.getOutputStream().write("X-Test: ${"a".repeat(5500)}\r\n".toByteArray())
                client.getOutputStream().write("X-Test: ${"b".repeat(5500)}\r\n".toByteArray())
                client.getOutputStream().write("X-Test: ${"c".repeat(5500)}\r\n\r\n".toByteArray())
                client.getOutputStream().flush()

                assertEquals(true, client.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 400"))
            } finally {
                client.close()
                accepted.close()
            }
        }
    }
}
