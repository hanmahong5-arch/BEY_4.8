/*
* DB changes since 937026f (28.11.2025)
 */

DROP TABLE IF EXISTS advent;
CREATE TABLE advent (
  account_id integer NOT NULL,
  last_day_received date NOT NULL,
  PRIMARY KEY (account_id)
);

-- Solo fortress ownership (单人要塞) — extends siege_locations with per-player ownership.
ALTER TABLE siege_locations ADD COLUMN IF NOT EXISTS owner_player_id integer NOT NULL DEFAULT 0;
ALTER TABLE siege_locations ADD COLUMN IF NOT EXISTS owner_captured_at bigint NOT NULL DEFAULT 0;
