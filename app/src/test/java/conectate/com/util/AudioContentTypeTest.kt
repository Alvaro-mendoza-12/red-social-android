package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class AudioContentTypeTest {
    @Test fun browserContainerTypesAndParametersAreAccepted() {
        listOf("audio/webm;codecs=opus", "video/webm", "Audio/Ogg", "application/ogg", "video/mp4", "application/octet-stream")
            .forEach { assertTrue(it, isAudioDeliveryContentType(it)) }
    }

    @Test fun htmlJsonImagesAndArchivesAreNotTreatedAsAudio() {
        listOf("text/html", "application/json", "image/png", "application/zip")
            .forEach { assertFalse(it, isAudioDeliveryContentType(it)) }
    }
}
