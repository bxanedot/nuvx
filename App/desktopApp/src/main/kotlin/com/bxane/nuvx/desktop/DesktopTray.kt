package com.bxane.nuvx.desktop

import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

internal class DesktopTray(
    onShow: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQuit: () -> Unit,
) : AutoCloseable {
    private val icon = TrayIcon(loadIcon(), "nuvx").apply {
        isImageAutoSize = true
        popupMenu = PopupMenu().apply {
            add(item("Open nuvx", onShow))
            addSeparator()
            add(item("Play / pause", onPlayPause))
            add(item("Previous", onPrevious))
            add(item("Next", onNext))
            addSeparator()
            add(item("Exit nuvx", onQuit))
        }
        addActionListener { EventQueue.invokeLater { onShow() } }
    }
    private var installed = false

    fun install(): Boolean = runCatching {
        SystemTray.getSystemTray().add(icon)
        installed = true
    }.isSuccess

    override fun close() {
        if (installed) runCatching { SystemTray.getSystemTray().remove(icon) }
        installed = false
    }

    private fun item(label: String, action: () -> Unit) = MenuItem(label).apply {
        addActionListener { EventQueue.invokeLater { action() } }
    }

    companion object {
        const val ICON = "/nuvx_mark_white.png"

        private fun supported() = runCatching {
            SystemTray.isSupported() && DesktopTray::class.java.getResource(ICON) != null
        }.getOrDefault(false)

        private fun loadIcon() = requireNotNull(DesktopTray::class.java.getResourceAsStream(ICON)).use {
            requireNotNull(ImageIO.read(it)) { "Invalid nuvx tray icon" }
        }

        val isSupported: Boolean get() = supported()
    }
}
