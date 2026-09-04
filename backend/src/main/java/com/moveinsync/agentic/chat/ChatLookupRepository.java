package com.moveinsync.agentic.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small helper for ChatService: turns free-text mentions of a vendor
 * ("Vendor A", "V1") or a pickup zone ("Marathahalli") into the canonical
 * id/name the rest of the app already keys on - so a chat question can
 * drill into the same attribution/benchmarking services everything else
 * uses, filtered to the thing the user actually asked about.
 */
@Repository
public class ChatLookupRepository {

    private final JdbcTemplate jdbc;

    public ChatLookupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> vendors() {
        return jdbc.queryForList("SELECT vendor_id, vendor_name FROM vendors ORDER BY vendor_id");
    }

    public List<String> zones() {
        return jdbc.queryForList(
                "SELECT DISTINCT pickup_zone FROM employees WHERE pickup_zone IS NOT NULL ORDER BY pickup_zone",
                String.class);
    }

    /** Returns the matched vendor_id (e.g. "V1"), or null if the message doesn't mention one. */
    public String matchVendor(String lowerCaseMessage) {
        for (Map<String, Object> v : vendors()) {
            String id = String.valueOf(v.get("vendor_id"));
            String name = String.valueOf(v.get("vendor_name"));
            if (lowerCaseMessage.contains(id.toLowerCase(Locale.ROOT))
                    || lowerCaseMessage.contains(name.toLowerCase(Locale.ROOT))) {
                return id;
            }
        }
        return null;
    }

    /** Returns the matched pickup zone name, or null if the message doesn't mention one. */
    public String matchZone(String lowerCaseMessage) {
        for (String zone : zones()) {
            if (lowerCaseMessage.contains(zone.toLowerCase(Locale.ROOT))) {
                return zone;
            }
        }
        return null;
    }
}
