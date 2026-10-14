package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionButton(
    val subscribeButtonRenderer: SubscribeButtonRenderer,
) {
    @Serializable
    data class SubscribeButtonRenderer(
        val subscribed: Boolean,
        val channelId: String,
        /** The count alone, "7.19M" or "7,19 M", with no word after it. */
        val subscriberCountText: Runs? = null,
    )
}