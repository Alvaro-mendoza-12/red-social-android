package conectate.com.util

/** Membership activity belongs to the room; it has no human message sender. */
fun isCollectionMembershipNotice(contentType: String?): Boolean =
    contentType?.trim()?.equals("system_membership", ignoreCase = true) == true
