package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val searchEndpoint: SearchEndpoint? = null,
    val queueAddEndpoint: QueueAddEndpoint? = null,
    val shareEntityEndpoint: ShareEntityEndpoint? = null,
    /** A link out of YouTube, such as the Wikipedia page an artist's description is taken from. */
    val urlEndpoint: UrlEndpoint? = null,
) {
    val endpoint: Endpoint?
        get() = watchEndpoint
            ?: watchPlaylistEndpoint
            ?: browseEndpoint
            ?: searchEndpoint
            ?: queueAddEndpoint
            ?: shareEntityEndpoint

    val anyWatchEndpoint: WatchEndpoint?
        get() = watchEndpoint
            ?: watchPlaylistEndpoint
}

@Serializable
data class UrlEndpoint(
    val url: String,
)
