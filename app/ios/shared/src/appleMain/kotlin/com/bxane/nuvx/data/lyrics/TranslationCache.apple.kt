@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bxane.nuvx.data.lyrics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile




























private const val MAX_ENTRIES = 200

private const val CACHE_DIRECTORY = "lyrics-translation"

private const val UTF8 = 4uL

internal actual fun read(key: String): String? {
    val path = entryPath(key) ?: return null
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    return NSString.stringWithContentsOfFile(path, UTF8, null)?.toString()
}









internal actual fun write(key: String, document: String) {
    val path = entryPath(key) ?: return
    NSString.create(string = document).writeToFile(path, true, UTF8, null)
    prune()
}

internal actual fun removeAll() {
    directory()?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
    directory()
}

private fun prune() {
    val root = directory() ?: return
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(root, null) as? List<*> ?: return
    if (names.size <= MAX_ENTRIES) return




    val dated = names.filterIsInstance<String>()
        .mapNotNull { name ->
            val modified = manager.attributesOfItemAtPath("$root/$name", null)
                ?.get(NSFileModificationDate) as? Double
            name to (modified ?: 0.0)
        }
        .sortedBy { it.second }
    dated.take(names.size - MAX_ENTRIES).forEach { (name, _) ->
        manager.removeItemAtPath("$root/$name", null)
    }
}


private fun directory(): String? {
    val base = NSFileManager.defaultManager.URLsForDirectory(
        NSCachesDirectory,
        NSUserDomainMask,
    ).firstOrNull() as? NSURL ?: return null
    val path = base.path ?: return null
    val directory = "$path/$CACHE_DIRECTORY"
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(directory)) {
        manager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
    return directory
}

private fun entryPath(key: String): String? = directory()?.let { "$it/${key.stableHash()}.json" }








internal fun String.stableHash(): String {
    var hash = -0x340d631b7bdddcdbL
    for (ch in this) {
        hash = hash xor ch.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
