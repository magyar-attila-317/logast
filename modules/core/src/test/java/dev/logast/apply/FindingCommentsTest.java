package dev.logast.apply;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FindingCommentsTest {
    @Test
    void removesStaleMarkerFollowedByAnotherComment() {
        // a metrics "skip" appends // logast:ignore at the end of the line, after the marker
        String source = "log.info(\"Pledge sent\"); // logast: METRIC_OUTCOME_UNMEASURED OUM-027987 // logast:ignore METRIC_OUTCOME_UNMEASURED\n";
        assertThat(FindingComments.removeStaleMarkers(source, Set.of()))
                .isEqualTo("log.info(\"Pledge sent\"); // logast:ignore METRIC_OUTCOME_UNMEASURED\n");
    }

    @Test
    void keepsActiveMarkersAndOtherCommentText() {
        String source = "log.info(\"a\"); // keep this; logast: LOG_FORMAT FMT-1a2b3c; MESSAGE_VAGUE MVG-4d5e6f\r\n"
                + "log.info(\"b\"); // logast: LOG_FORMAT FMT-aaaaaa\n";
        assertThat(FindingComments.removeStaleMarkers(source, Set.of("MVG-4d5e6f")))
                .isEqualTo("log.info(\"a\"); // keep this; logast: MESSAGE_VAGUE MVG-4d5e6f\r\n"
                        + "log.info(\"b\");\n");
    }

    @Test
    void leavesUrlsAndOtherLogastCommentsAlone() {
        String source = "log.info(\"see http://example.org\"); // logast:ignore LOG_FORMAT\n";
        assertThat(FindingComments.removeStaleMarkers(source, Set.of())).isEqualTo(source);
    }
}
