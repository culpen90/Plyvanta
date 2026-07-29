package app.plyvanta.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSourceException;

import org.junit.Test;

import java.io.IOException;

public final class EncryptedMediaDataSourceRangeTest {
    @Test
    public void unsetAndBoundedRequestsExposeOnlyBytesThroughEndOfResource()
            throws Exception {
        assertEquals(
                900L,
                EncryptedMediaDataSource.resolveReadableLength(
                        100L,
                        C.LENGTH_UNSET,
                        1_000L
                )
        );
        assertEquals(
                250L,
                EncryptedMediaDataSource.resolveReadableLength(
                        100L,
                        250L,
                        1_000L
                )
        );
        assertEquals(
                100L,
                EncryptedMediaDataSource.resolveReadableLength(
                        900L,
                        500L,
                        1_000L
                )
        );
        assertEquals(
                0L,
                EncryptedMediaDataSource.resolveReadableLength(
                        1_000L,
                        50L,
                        1_000L
                )
        );
        assertEquals(
                500L,
                EncryptedMediaDataSource.resolveReportedOpenLength(
                        500L,
                        100L
                )
        );
        assertEquals(
                900L,
                EncryptedMediaDataSource.resolveReportedOpenLength(
                        C.LENGTH_UNSET,
                        900L
                )
        );
    }

    @Test
    public void invalidPositionUsesMedia3PositionOutOfRangeReason() {
        IOException beyondEnd = assertThrows(
                IOException.class,
                () -> EncryptedMediaDataSource.resolveReadableLength(
                        1_001L,
                        C.LENGTH_UNSET,
                        1_000L
                )
        );
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(beyondEnd));

        IOException negative = assertThrows(
                IOException.class,
                () -> EncryptedMediaDataSource.resolveReadableLength(
                        -1L,
                        C.LENGTH_UNSET,
                        1_000L
                )
        );
        assertTrue(DataSourceException.isCausedByPositionOutOfRange(negative));
    }

    @Test
    public void invalidLengthsFailClosed() {
        assertThrows(
                IOException.class,
                () -> EncryptedMediaDataSource.resolveReadableLength(
                        0L,
                        -2L,
                        1_000L
                )
        );
        assertThrows(
                IOException.class,
                () -> EncryptedMediaDataSource.resolveReadableLength(
                        0L,
                        C.LENGTH_UNSET,
                        -1L
                )
        );
    }
}
