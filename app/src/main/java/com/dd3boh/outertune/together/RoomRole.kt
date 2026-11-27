package com.dd3boh.outertune.together

/**
 * What the room sheet tells this phone about its place, in a line or two at the top (testers, 22 Nov 2026: "what does
 * control do", "can anyone add songs?"). One role per situation, so each line can be held to what the code does.
 */
enum class RoomRole {
    /** In control of the music (the owner, or a guest it handed control to): chooses, skips, seeks, pauses for all. */
    IN_CONTROL,

    /** The owner while someone else is in control: can take control back. */
    OWNER_GAVE,

    /**
     * A guest while someone else is in control: adds songs, and mutes just for itself (26 Nov 2026, Spotify's Jam); with
     * control for everyone (RoomSettings.everyoneControls) its moves are the room's too, and the sheet says so.
     */
    GUEST;

    companion object {
        /**
         * Null while the sheet has something else to say instead: the host is away, or this phone still waits to hear who
         * is in control.
         */
        fun of(owner: Boolean, leading: Boolean, hostAway: Boolean, waitingForHost: Boolean): RoomRole? = when {
            leading -> IN_CONTROL
            hostAway || waitingForHost -> null
            owner -> OWNER_GAVE
            else -> GUEST
        }
    }
}
