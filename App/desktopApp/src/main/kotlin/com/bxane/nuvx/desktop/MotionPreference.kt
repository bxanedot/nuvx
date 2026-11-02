package com.bxane.nuvx.desktop

internal fun desktopReducedMotion(override: String?, systemAnimations: Boolean?): Boolean =
    override == "true" || systemAnimations == false
