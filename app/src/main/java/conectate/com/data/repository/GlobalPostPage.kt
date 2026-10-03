package conectate.com.data.repository

import conectate.com.data.model.Post

internal data class GlobalPostPage(val items: List<Post>, val nextOffset: Int?)
