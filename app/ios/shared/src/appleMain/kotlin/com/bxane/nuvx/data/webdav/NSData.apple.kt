package com.bxane.nuvx.data.webdav

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.posix.memcpy









@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.asNSData(): NSData {
    val data = NSMutableData()
    if (isEmpty()) return data
    data.setLength(size.convert())
    usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.convert()) }
    return data
}
