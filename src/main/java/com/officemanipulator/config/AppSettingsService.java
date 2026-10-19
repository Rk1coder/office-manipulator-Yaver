package com.officemanipulator.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Small key-value override store for settings that must take effect at runtime without an app
 * restart (e.g. the Claude API key entered on the Settings page) — backed by the {@code app_settings}
 * table. A missing key means "no override"; callers fall back to their env/application.yml default.
 */
@Service
public class AppSettingsService {
    private final JdbcTemplate db;

    public AppSettingsService(JdbcTemplate db) {
        this.db = db;
    }

    /** Returns the stored override for {@code key}, or {@code envDefault} if none is set. */
    public String get(String key, String envDefault) {
        List<String> rows = db.queryForList("SELECT value FROM app_settings WHERE key=?", String.class, key);
        return rows.isEmpty() ? envDefault : rows.getFirst();
    }

    public boolean isSet(String key) {
        return !db.queryForList("SELECT 1 FROM app_settings WHERE key=?", Integer.class, key).isEmpty();
    }

    /** A blank/null value clears the override, reverting to the env default. */
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            db.update("DELETE FROM app_settings WHERE key=?", key);
            return;
        }
        db.update("""
                INSERT INTO app_settings(key, value, updated_at) VALUES (?, ?, now())
                ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()
                """, key, value);
    }

    public Map<String, String> getAll() {
        return db.queryForList("SELECT key, value FROM app_settings").stream()
                .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("key"), row -> (String) row.get("value")));
    }

    /** Store dependent settings atomically, so verification cannot apply to a different key/model. */
    @Transactional
    public void setAll(Map<String, String> values) {
        values.forEach(this::set);
    }
}
