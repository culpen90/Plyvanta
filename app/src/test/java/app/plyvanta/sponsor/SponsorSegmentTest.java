package app.plyvanta.sponsor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SponsorSegmentTest {
    @Test
    public void exposesPlayerFriendlyValues() {
        SponsorSegment segment = new SponsorSegment("segment-id", 1.2344d, 5.6786d, "sponsor");

        assertEquals("segment-id", segment.getUuid());
        assertEquals("sponsor", segment.getCategory());
        assertEquals(1.2344d, segment.getStartSeconds(), 0.0d);
        assertEquals(5.6786d, segment.getEndSeconds(), 0.0d);
        assertEquals(1_234L, segment.getStartMillis());
        assertEquals(5_679L, segment.getEndMillis());
        assertTrue(segment.contains(1.2344d));
        assertTrue(segment.contains(5.0d));
        assertFalse(segment.contains(5.6786d));
    }

    @Test
    public void rejectsInvalidIntervalsAndMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SponsorSegment("id", -1.0d, 2.0d, "sponsor")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SponsorSegment("id", 2.0d, 2.0d, "sponsor")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SponsorSegment("id", 1.0d, Double.POSITIVE_INFINITY, "sponsor")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SponsorSegment(" ", 1.0d, 2.0d, "sponsor")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SponsorSegment("id", 1.0d, 2.0d, "")
        );
    }

    @Test
    public void mergingRetainsAllSourceMetadata() {
        SponsorSegment first = new SponsorSegment("uuid-a", 5.0d, 10.0d, "sponsor");
        SponsorSegment second = new SponsorSegment("uuid-b", 9.0d, 12.0d, "selfpromo");

        SponsorSegment merged = first.mergeWith(second);

        assertEquals(5.0d, merged.getStartSeconds(), 0.0d);
        assertEquals(12.0d, merged.getEndSeconds(), 0.0d);
        assertEquals("uuid-a", merged.getUuid());
        assertEquals("sponsor", merged.getCategory());
        assertEquals(Arrays.asList("uuid-a", "uuid-b"), merged.getUuids());
        assertEquals(Arrays.asList("sponsor", "selfpromo"), merged.getCategories());
        assertThrows(
                UnsupportedOperationException.class,
                () -> merged.getCategories().add("intro")
        );
    }

    @Test
    public void refusesToMergeSeparatedIntervals() {
        SponsorSegment first = new SponsorSegment("uuid-a", 1.0d, 2.0d, "sponsor");
        SponsorSegment second = new SponsorSegment("uuid-b", 3.0d, 4.0d, "sponsor");

        assertThrows(IllegalArgumentException.class, () -> first.mergeWith(second));
        assertEquals(Collections.singletonList("uuid-a"), first.getUuids());
    }
}
