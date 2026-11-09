// SPDX-License-Identifier: GPL-3.0-only
package com.my.kizzy.gateway.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Heartbeat(
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long,
)
