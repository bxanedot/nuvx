package com.licht.nuvx.data.sync

import java.io.File
import java.util.concurrent.TimeUnit

/** The real Nuvx Sync server (`core/sync`) on a free port with an in-memory database. Needs Node 22.18+ on PATH. */
class SyncTestServer : AutoCloseable {
    private val process: Process = ProcessBuilder("node", "src/server.ts")
        .directory(File("../sync"))
        .redirectErrorStream(true)
        .apply { environment().putAll(mapOf("NUVX_SYNC_DB" to ":memory:", "NUVX_SYNC_PORT" to "0")) }
        .start()

    val url: String = process.inputStream.bufferedReader().readLine().let { line ->
        "http://" + (line?.let { Regex("listening on (\\S+)").find(it)?.groupValues?.get(1) }
            ?: error("sync server did not start: $line"))
    }

    override fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
