package conectate.com.data.repository

import conectate.com.data.model.SemanticSearchResult

data class PublicContentSearchPage(val items: List<SemanticSearchResult>, val nextOffset: Int?)
