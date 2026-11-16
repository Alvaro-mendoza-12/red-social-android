package conectate.com.presentation.components;

import conectate.com.data.model.Message;
import java.util.List;
import java.util.TimeZone;
import kotlinx.serialization.json.Json;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** The real wire model and album policy; no UI, media requests or account credentials. */
public class MediaGridDateBoundaryTest {
    private TimeZone originalZone;

    @Before public void setLocalZone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota"));
    }

    @After public void restoreLocalZone() { TimeZone.setDefault(originalZone); }

    private Message media(String id, String date) {
        return media(id, date, "image");
    }

    private Message media(String id, String date, String type) {
        String timestamp = date == null ? "null" : "\"" + date + "\"";
        String json = "{\"id\":\"" + id + "\",\"sender_id\":\"sender\","
            + "\"content_type\":\"" + type + "\",\"media_url\":\"https://example.invalid/" + id
            + ".png\",\"created_at\":" + timestamp + "}";
        return Json.Default.decodeFromString(Message.Companion.serializer(), json);
    }

    private List<Message> cluster(List<Message> messages, int index) {
        return MediaGridMessageBubbleKt.mediaGridClusterAt(messages, index, 60_000L);
    }

    @Test public void localMidnightEndsThePreviousAlbum() {
        List<Message> messages = List.of(media("before", "2026-11-15T23:59:50-05:00"),
            media("after", "2026-11-16T05:00:10Z"));
        assertEquals(1, cluster(messages, 0).size());
    }

    @Test public void firstImageOfTheNewDayRemainsAVisibleRow() {
        List<Message> messages = List.of(media("before", "2026-11-16T04:59:50Z"),
            media("after", "2026-11-16T05:00:10Z"));
        assertNotNull(cluster(messages, 1));
        assertEquals("after", cluster(messages, 1).get(0).getId());
    }

    @Test public void consecutiveAlbumsKeepTheirOwnDays() {
        List<Message> messages = List.of(media("a", "2026-11-16T04:59:30Z"),
            media("b", "2026-11-16T04:59:45Z"), media("c", "2026-11-16T05:00:10Z"),
            media("d", "2026-11-16T05:00:30Z"));
        assertEquals(2, cluster(messages, 0).size());
        assertNull(cluster(messages, 1));
        assertEquals(2, cluster(messages, 2).size());
        assertNull(cluster(messages, 3));
    }

    @Test public void unknownDatesCannotInventACommonAlbumDay() {
        List<List<Message>> cases = List.of(
            List.of(media("invalid", "invalid"), media("missing", null)),
            List.of(media("a", "invalid-a"), media("b", "invalid-b")),
            List.of(media("a", null), media("b", null)));
        for (List<Message> messages : cases) {
            assertEquals(1, cluster(messages, 0).size());
            assertNotNull(cluster(messages, 1));
        }
    }

    @Test public void utcMidnightInsideTheSameLocalDayKeepsTheAlbum() {
        List<Message> messages = List.of(media("before", "2026-11-15T23:59:50Z"),
            media("after", "2026-11-16T00:00:10Z"));
        assertEquals(2, cluster(messages, 0).size());
        assertNull(cluster(messages, 1));
    }

    @Test public void ordinarySameDayImagesStillGroup() {
        List<Message> messages = List.of(media("a", "2026-11-15T18:07:00-05:00"),
            media("b", "2026-11-15T23:07:20Z"));
        assertEquals(2, cluster(messages, 0).size());
        assertNull(cluster(messages, 1));
    }

    @Test public void stickersRemainOutsideAlbums() {
        List<Message> messages = List.of(media("a", "2026-11-15T23:07:00Z", "sticker"),
            media("b", "2026-11-15T23:07:20Z", "sticker"));
        assertEquals(1, cluster(messages, 0).size());
        assertEquals(1, cluster(messages, 1).size());
    }

    @Test public void theExistingTimeGapStillSeparatesDistantImages() {
        List<Message> messages = List.of(media("a", "2026-11-15T23:07:00Z"),
            media("b", "2026-11-15T23:08:01Z"));
        assertEquals(1, cluster(messages, 0).size());
        assertEquals(1, cluster(messages, 1).size());
    }
}
