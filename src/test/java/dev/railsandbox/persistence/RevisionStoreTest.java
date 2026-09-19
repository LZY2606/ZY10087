package dev.railsandbox.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RevisionStoreTest {
    RevisionStore store;
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("revision-test")
                .addScript("classpath:schema.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        store = new RevisionStore(jdbc);
    }

    @Test
    void rawEvidenceIsAppendOnlyAndStaleBaseWritesConflict() {
        Revision first = store.submit("rule", "candidate", null,
                "{\"id\":\"candidate\",\"name\":\"v1\",\"mutualExclusion\":true,\"flankProtection\":true,\"orderedRelease\":true,\"signalProtection\":true}",
                "test", Map.of(), null);
        Revision second = store.submit("rule", "candidate", first.revisionId(),
                "{\"id\":\"candidate\",\"name\":\"v2\",\"mutualExclusion\":true,\"flankProtection\":false,\"orderedRelease\":true,\"signalProtection\":true}",
                "test", Map.of(), null);

        RevisionConflictException conflict = assertThrows(RevisionConflictException.class, () ->
                store.submit("rule", "candidate", first.revisionId(),
                        "{\"id\":\"candidate\",\"name\":\"late\",\"mutualExclusion\":false,\"flankProtection\":true,\"orderedRelease\":true,\"signalProtection\":true}",
                        "other-browser", Map.of(), null));
        assertEquals(second.revisionId(), ((Map<?, ?>) conflict.body()).get("currentRevisionId"));

        Integer evidenceRows = jdbc.queryForObject("SELECT COUNT(*) FROM raw_evidence", Integer.class);
        assertEquals(2, evidenceRows);
        assertDoesNotThrow(() -> jdbc.queryForMap("SELECT payload FROM raw_evidence WHERE id = ?", first.evidenceId()));
    }
}
