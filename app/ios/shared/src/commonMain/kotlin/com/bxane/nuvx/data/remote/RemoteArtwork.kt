package com.bxane.nuvx.data.remote








object RemoteArtwork {













    fun pick(siblingUrls: List<String>): String? {
        if (siblingUrls.isEmpty()) return null
        return siblingUrls.sortedWith(
            compareBy(



                { stem(it).let { s -> preferredStems.indexOfFirst { s.startsWith(it) }.takeIf { i -> i >= 0 } ?: Int.MAX_VALUE } },
                { stem(it) },
            )
        ).firstOrNull()
    }







    fun stem(fileUrl: String): String {
        val path = fileUrl.substringBefore('?').substringBefore('#').trimEnd('/')
        val segment = path.substringAfterLast('/')
        return Percent.decode(segment).substringBeforeLast('.').lowercase()
    }










    private val preferredStems = listOf("cover", "folder", "front", "albumart", "album", "artwork")
}
