-- ============================================================
-- BEY_4.8 Custom Schema — Shiguang Living World Systems
-- Run once on aion_gs database to initialize SEEE tables.
-- ============================================================

-- Epoch metadata: tracks each time-limited server epoch (season)
CREATE TABLE IF NOT EXISTS epoch (
    id          SERIAL PRIMARY KEY,
    started_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    ended_at    TIMESTAMP,
    title       VARCHAR(128) NOT NULL DEFAULT '第一纪元·拾光初醒',
    outcome     VARCHAR(16),       -- ELYOS_WIN / ASMO_WIN / DRAW / CHAOS / BALAUR
    player_count INT NOT NULL DEFAULT 0
);

-- Seed the first epoch on first run
INSERT INTO epoch (title) VALUES ('第一纪元·拾光初醒')
ON CONFLICT DO NOTHING;

-- World chronicle: permanent record of history-making events
CREATE TABLE IF NOT EXISTS world_chronicle (
    id            SERIAL PRIMARY KEY,
    occurred_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    epoch_id      INT NOT NULL DEFAULT 1,
    event_type    VARCHAR(32) NOT NULL,
    -- Event types: SIEGE_CAPTURE, SIEGE_DEFEND, SIEGE_BALAUR,
    --              HEIRLOOM_DROP, HEIRLOOM_TRANSFER, BOSS_FIRST_KILL,
    --              EPOCH_BEGIN, EPOCH_END
    faction       VARCHAR(16),           -- ELYOS / ASMO / BALAUR / null
    location_id   INT NOT NULL DEFAULT 0,
    location_name VARCHAR(128),
    protagonist   VARCHAR(128),          -- player name or legion name
    title         VARCHAR(256) NOT NULL, -- short headline
    narrative     TEXT,                  -- full flavour paragraph
    importance    SMALLINT NOT NULL DEFAULT 3  -- 1=legendary 3=notable 5=minor
);

-- Epoch heirloom: tracks the 3 per-epoch legendary drops
CREATE TABLE IF NOT EXISTS epoch_heirloom (
    item_obj_id      BIGINT PRIMARY KEY,
    epoch_id         INT NOT NULL DEFAULT 1,
    item_template_id INT NOT NULL,
    dropped_from_npc_id INT,
    dropped_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    first_owner      VARCHAR(64) NOT NULL,
    current_owner    VARCHAR(64) NOT NULL,
    transfer_count   INT NOT NULL DEFAULT 0
);

-- Heirloom transfer history: every owner change is recorded forever
CREATE TABLE IF NOT EXISTS heirloom_history (
    id            SERIAL PRIMARY KEY,
    item_obj_id   BIGINT NOT NULL,
    from_player   VARCHAR(64),       -- null = original drop
    to_player     VARCHAR(64) NOT NULL,
    transferred_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes for query performance
CREATE INDEX IF NOT EXISTS idx_chronicle_epoch ON world_chronicle(epoch_id);
CREATE INDEX IF NOT EXISTS idx_chronicle_type  ON world_chronicle(event_type);
CREATE INDEX IF NOT EXISTS idx_heirloom_epoch  ON epoch_heirloom(epoch_id);
CREATE INDEX IF NOT EXISTS idx_heirloom_hist   ON heirloom_history(item_obj_id);
