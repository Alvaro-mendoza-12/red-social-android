package conectate.com.data.repository.message

import conectate.com.data.local.entity.UserEntity
import conectate.com.data.model.Chat
import conectate.com.data.model.ChatWithLastMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxIdentityCacheTest {

    @Test
    fun `inbox warmup does not permanently starve contacts after the first 24`() {
        val selected = selectInboxAvatarsForPrefetch((1..140).map { "https://cdn.test/avatar-$it.jpg" })
        assertEquals(140, selected.size)
        assertTrue(selected.last().endsWith("avatar-140.jpg"))
    }

    @Test
    fun `partial inbox identity never erases cached avatar or name`() {
        val existing = user("person", "Nombre guardado", "https://cdn.test/avatar.jpg")
        val partial = user("person", "Usuario", null)

        val merged = mergeInboxIdentity(existing, partial)

        assertEquals("Nombre guardado", merged.displayName)
        assertEquals("https://cdn.test/avatar.jpg", merged.avatar_url)
    }

    @Test
    fun `new complete inbox identity refreshes cached avatar`() {
        val existing = user("person", "Nombre anterior", "https://cdn.test/old.jpg")
        val fresh = user("person", "Nombre actual", "https://cdn.test/new.jpg")

        val merged = mergeInboxIdentity(existing, fresh)

        assertEquals("Nombre actual", merged.displayName)
        assertEquals("https://cdn.test/new.jpg", merged.avatar_url)
    }

    @Test
    fun `avatar prefetch is bounded deduplicated and excludes video identities`() {
        val selected = selectInboxAvatarsForPrefetch(
            urls = listOf(
                " https://one.test/storage/v1/object/public/avatars/u/photo.jpg ",
                "https://two.test/storage/v1/object/public/avatars/u/photo.jpg",
                "https://cdn.test/avatar.mp4",
                "https://cdn.test/second.png",
            ),
            maxItems = 2,
        )

        assertEquals(2, selected.size)
        assertTrue(selected.first().endsWith("photo.jpg"))
        assertTrue(selected.last().endsWith("second.png"))
        assertFalse(selected.any { it.endsWith(".mp4") })
    }

    @Test
    fun `partial group refresh preserves cached name and image`() {
        val cached = conversation(
            isGroup = true,
            name = "Familia",
            image = "https://cdn.test/groups/family.jpg",
        )
        val partial = conversation(isGroup = true, name = "Chat del Sistema", image = null)

        val merged = mergeInboxConversationIdentity(cached, partial)

        assertEquals("Familia", merged.chat.group_name)
        assertEquals("https://cdn.test/groups/family.jpg", merged.chat.group_image)
        assertEquals("https://cdn.test/groups/family.jpg", merged.otherUserImage)
    }

    @Test
    fun `group image participates in inbox avatar warmup`() {
        val group = conversation(
            isGroup = true,
            name = "Equipo",
            image = "https://cdn.test/groups/team.jpg",
        )

        assertEquals("https://cdn.test/groups/team.jpg", inboxAvatarUrl(group))
    }

    private fun user(id: String, name: String, avatar: String?) = UserEntity(
        id = id,
        username = name,
        full_name = name,
        avatar_url = avatar,
        bio = null,
    )

    private fun conversation(isGroup: Boolean, name: String, image: String?) = ChatWithLastMessage(
        chat = Chat(
            id = "chat",
            is_group = isGroup,
            group_name = name,
            group_image = image,
        ),
        otherUserName = name,
        otherUserImage = image,
        otherUserId = if (isGroup) null else "person",
    )
}
