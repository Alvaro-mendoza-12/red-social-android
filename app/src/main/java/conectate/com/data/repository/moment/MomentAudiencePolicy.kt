package conectate.com.data.repository.moment

/** The same display guard must apply to a feed refresh and a realtime insert. */
internal fun canDisplayMomentAudience(
    authorId: String,
    viewerId: String,
    visibilityType: String,
    visibility: String,
    excludedIds: List<String>,
    audienceIds: List<String>,
): Boolean {
    if (authorId == viewerId) return true
    if (visibilityType in setOf("silent", "private")) return false
    if (viewerId in excludedIds) return false
    return visibility != "only_selected" || viewerId in audienceIds
}
