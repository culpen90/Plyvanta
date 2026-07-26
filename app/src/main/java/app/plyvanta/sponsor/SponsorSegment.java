package app.plyvanta.sponsor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, validated SponsorBlock interval.
 *
 * <p>A segment can contain more than one UUID or category when overlapping intervals have been
 * merged. The singular getters return the first deterministic value for simple player UIs, while
 * the plural getters retain all source metadata.
 */
public final class SponsorSegment {
    private final double startSeconds;
    private final double endSeconds;
    private final List<String> uuids;
    private final List<String> categories;

    public SponsorSegment(
            String uuid,
            double startSeconds,
            double endSeconds,
            String category
    ) {
        this(
                startSeconds,
                endSeconds,
                Collections.singletonList(requireText(uuid, "uuid")),
                Collections.singletonList(requireText(category, "category"))
        );
    }

    private SponsorSegment(
            double startSeconds,
            double endSeconds,
            List<String> uuids,
            List<String> categories
    ) {
        if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)) {
            throw new IllegalArgumentException("Segment times must be finite");
        }
        if (startSeconds < 0.0d) {
            throw new IllegalArgumentException("Segment start must not be negative");
        }
        if (endSeconds <= startSeconds) {
            throw new IllegalArgumentException("Segment end must be after its start");
        }

        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.uuids = immutableDistinctText(uuids, "uuid");
        this.categories = immutableDistinctText(categories, "category");
    }

    public String getUuid() {
        return uuids.get(0);
    }

    public List<String> getUuids() {
        return uuids;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public double getEndSeconds() {
        return endSeconds;
    }

    public long getStartMillis() {
        return Math.round(startSeconds * 1_000.0d);
    }

    public long getEndMillis() {
        return Math.round(endSeconds * 1_000.0d);
    }

    public String getCategory() {
        return categories.get(0);
    }

    public List<String> getCategories() {
        return categories;
    }

    public boolean contains(double positionSeconds) {
        return Double.isFinite(positionSeconds)
                && positionSeconds >= startSeconds
                && positionSeconds < endSeconds;
    }

    SponsorSegment mergeWith(SponsorSegment other) {
        Objects.requireNonNull(other, "other");
        if (other.startSeconds > endSeconds || startSeconds > other.endSeconds) {
            throw new IllegalArgumentException("Only touching or overlapping segments can merge");
        }

        List<String> mergedUuids = new ArrayList<>(uuids);
        mergedUuids.addAll(other.uuids);
        List<String> mergedCategories = new ArrayList<>(categories);
        mergedCategories.addAll(other.categories);

        return new SponsorSegment(
                Math.min(startSeconds, other.startSeconds),
                Math.max(endSeconds, other.endSeconds),
                mergedUuids,
                mergedCategories
        );
    }

    private static List<String> immutableDistinctText(List<String> values, String name) {
        Objects.requireNonNull(values, name + "s");
        Set<String> distinctValues = new LinkedHashSet<>();
        for (String value : values) {
            distinctValues.add(requireText(value, name));
        }
        if (distinctValues.isEmpty()) {
            throw new IllegalArgumentException("At least one " + name + " is required");
        }
        return Collections.unmodifiableList(new ArrayList<>(distinctValues));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SponsorSegment)) {
            return false;
        }
        SponsorSegment that = (SponsorSegment) other;
        return Double.compare(startSeconds, that.startSeconds) == 0
                && Double.compare(endSeconds, that.endSeconds) == 0
                && uuids.equals(that.uuids)
                && categories.equals(that.categories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startSeconds, endSeconds, uuids, categories);
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT,
                "SponsorSegment{start=%.3f, end=%.3f, categories=%s, uuids=%s}",
                startSeconds,
                endSeconds,
                categories,
                uuids
        );
    }
}
