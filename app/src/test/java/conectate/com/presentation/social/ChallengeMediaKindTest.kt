package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class ChallengeMediaKindTest {
    @Test fun aCameraVideoWithTheRepositoryVersionSuffixRemainsAVideo() {
        assertEquals(ChallengeMediaKind.VIDEO, challengeMediaKind("camera",
            "https://media.example/storage/v1/object/public/challenges/challenge_submissions/round/user.mp4?v=1793530000000"))
    }
    @Test fun signedAndFragmentedVideoPathsAreRecognizedWithoutReadingTheQuery() {
        for (extension in listOf("MP4", "webm", "MOV", "m3u8", "3gp"))
            assertEquals(ChallengeMediaKind.VIDEO, challengeMediaKind("camera", "https://media.example/a.$extension?token=secret#position"))
    }
    @Test fun anAudioAnswerUsingAnMp4ContainerUsesTheAudioPlayer() {
        assertEquals(ChallengeMediaKind.AUDIO, challengeMediaKind("audio", "https://media.example/voice.mp4?v=12"))
    }
    @Test fun aVideoExtensionInAnImageQueryDoesNotTurnTheImageIntoAVideo() {
        assertEquals(ChallengeMediaKind.IMAGE, challengeMediaKind("camera", "https://media.example/image.jpg?name=a.mp4"))
        assertEquals(ChallengeMediaKind.IMAGE, challengeMediaKind("drawing", "https://media.example/videos/poster.png"))
    }
    @Test fun aLegacyVideoFolderWithAnOpaqueObjectStillUsesVideo() {
        assertEquals(ChallengeMediaKind.VIDEO, challengeMediaKind("mixed", "https://media.example/videos/object-id?download=1"))
    }
}
