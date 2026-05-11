-- V1 only created buildings/floors/access_points. Create system_settings here (matches SystemSettings entity).
CREATE TABLE IF NOT EXISTS system_settings (
    id BIGSERIAL PRIMARY KEY,
    unconfirmed_fencing_enabled BOOLEAN NOT NULL DEFAULT true,
    auto_threshold_seconds BIGINT NOT NULL DEFAULT 300,
    mandatory_fence_days INTEGER NOT NULL DEFAULT 14,
    encounter_window_days INTEGER NOT NULL DEFAULT 14
);

INSERT INTO system_settings (unconfirmed_fencing_enabled, auto_threshold_seconds, mandatory_fence_days, encounter_window_days)
SELECT true, 300, 14, 14
WHERE NOT EXISTS (SELECT 1 FROM system_settings);
