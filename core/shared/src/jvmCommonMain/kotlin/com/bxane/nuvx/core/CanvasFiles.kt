package com.bxane.nuvx.core

import java.io.File
import java.io.IOException
import java.net.URL

/**
 * A canvas loops every few seconds, and players re-read their source on each loop; streaming it would re-download the same
 * file dozens of times per song. So it is downloaded once into [dir] and played from there.
 * Keeps the [keep] most recently used files; files older than [maxAgeMs] are fetched again so a replaced canvas shows up.
 */
fun canvasFile(url: String, dir: File, keep: Int = 24, maxBytes: Long = 12L * 1024 * 1024, maxAgeMs: Long = 24L * 60 * 60 * 1000, now: Long = System.currentTimeMillis()): File {
    dir.mkdirs()
    val file = File(dir, url.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "canvas.mp4" })
    if (!file.isFile || now - file.lastModified() > maxAgeMs) {
        val part = File(dir, "${file.name}.part")
        // Time limits: without them a stalled connection blocks this IO thread forever (closing the player doesn't interrupt it).
        URL(url).openConnection().apply { connectTimeout = 10_000; readTimeout = 20_000 }.getInputStream().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) { out.close(); part.delete(); throw IOException("canvas larger than $maxBytes bytes") }
                    out.write(buffer, 0, n)
                }
            }
        }
        if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) throw IOException("could not store canvas") }
    }
    file.setLastModified(now)
    dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    return file
}
