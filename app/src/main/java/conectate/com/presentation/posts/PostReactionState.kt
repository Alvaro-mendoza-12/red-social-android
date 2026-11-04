package conectate.com.presentation.posts

import conectate.com.data.model.Post
import conectate.com.data.model.withPostReaction

internal fun Post.withReaction(selected: String?): Post {
    return withPostReaction(selected)
}
