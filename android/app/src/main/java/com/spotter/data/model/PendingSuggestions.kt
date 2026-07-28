package com.spotter.data.model

import kotlinx.serialization.Serializable

/**
 * The AI suggestion cards awaiting the user's Apply/Dismiss tap, serialized onto the assistant
 * message row that produced them (`chat_messages.suggestionsJson`, Room v15) so they survive
 * process death alongside the transcript.
 *
 * This is **local storage only** — it is never sent to or received from the server; the wire
 * shape stays [ChatResponse]. All four are nullable and mutually independent: a reply carries at
 * most one workout suggestion (adjustment > program > routine) plus an optional profile update,
 * and each is cleared as the user acts on it, so partially-filled envelopes are the normal case.
 *
 * See [com.spotter.ui.ai.AiChatViewModel] for the restore rules (only the newest assistant turn
 * restores; the adjustment is additionally scoped to its own workout session).
 */
@Serializable
data class PendingSuggestions(
    val routine: SuggestedRoutine? = null,
    val program: SuggestedProgram? = null,
    val adjustment: SuggestedAdjustment? = null,
    val profileUpdate: SuggestedProfileUpdate? = null,
) {
    /** Nothing left to show or store — the row's envelope should be cleared entirely. */
    fun isEmpty(): Boolean =
        routine == null && program == null && adjustment == null && profileUpdate == null
}
