

package com.bxane.nuvx.data.innertube

import com.bxane.nuvx.auth.normalizeDataSyncId
import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.model.AccountChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object InnertubeAccountParser {
    fun parseAccount(response: JsonElement): Account? {
        val header = InnertubeParser.collectRenderers(response, "activeAccountHeaderRenderer").firstOrNull()
            ?: return null
        val name = header.o("accountName").runs()
        if (name.isBlank()) return null
        val email = header.o("email").runs()
            .ifBlank { header.o("email").s("simpleText").orEmpty() }
            .ifBlank { header.o("channelHandle").runs() }
        return Account(
            name = name,
            email = email,
            thumbnailUrl = header.o("accountPhoto").a("thumbnails").best(),
        )
    }

    fun parseAccountChannels(root: JsonElement): List<AccountChannel> =
        InnertubeParser.collectRenderers(root, "accountItem").mapNotNull { item ->
            val name = item.o("accountName").runs()
                .ifBlank { item.o("accountName").s("simpleText").orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            val pageId = item.findString("pageId")
            val dataSyncId = normalizeDataSyncId(item.findString("datasyncIdToken"))
            if (pageId == null && dataSyncId == null) return@mapNotNull null
            AccountChannel(
                name = name,
                subtitle = item.o("channelHandle").runs()
                    .ifBlank { item.o("channelHandle").s("simpleText").orEmpty() }
                    .ifBlank { item.o("accountByline").runs() }
                    .ifBlank { item.o("accountByline").s("simpleText").orEmpty() },
                thumbnailUrl = item.o("accountPhoto").a("thumbnails").best(),
                pageId = pageId,
                dataSyncId = dataSyncId,
                activeOnWeb = (item["isSelected"] as? JsonPrimitive)?.content == "true",
            )
        }.distinctBy { it.key }
}
