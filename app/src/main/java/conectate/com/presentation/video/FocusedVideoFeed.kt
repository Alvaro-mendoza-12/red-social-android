package conectate.com.presentation.video

import conectate.com.data.model.Video

/** Keeps explicitly requested deep-link videos stable while feed pages race in. */
internal fun mergeFocusedVideoPage(
    focusedVideos: Collection<Video>,
    loadedPage: List<Video>,
): List<Video> = (focusedVideos + loadedPage).distinctBy { it.id }
