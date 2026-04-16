-- Drop all tables (CASCADE handles FK dependency order)
DROP TABLE IF EXISTS abyss_rank CASCADE;
DROP TABLE IF EXISTS account_passports CASCADE;
DROP TABLE IF EXISTS account_stamps CASCADE;
DROP TABLE IF EXISTS advent CASCADE;
DROP TABLE IF EXISTS announcements CASCADE;
DROP TABLE IF EXISTS blocks CASCADE;
DROP TABLE IF EXISTS bonus_packs CASCADE;
DROP TABLE IF EXISTS bookmark CASCADE;
DROP TABLE IF EXISTS broker CASCADE;
DROP TABLE IF EXISTS challenge_tasks CASCADE;
DROP TABLE IF EXISTS commands_access CASCADE;
DROP TABLE IF EXISTS craft_cooldowns CASCADE;
DROP TABLE IF EXISTS custom_instance CASCADE;
DROP TABLE IF EXISTS custom_instance_records CASCADE;
DROP TABLE IF EXISTS event CASCADE;
DROP TABLE IF EXISTS faction_packs CASCADE;
DROP TABLE IF EXISTS friends CASCADE;
DROP TABLE IF EXISTS guides CASCADE;
DROP TABLE IF EXISTS headhunting CASCADE;
DROP TABLE IF EXISTS house_bids CASCADE;
DROP TABLE IF EXISTS house_object_cooldowns CASCADE;
DROP TABLE IF EXISTS house_scripts CASCADE;
DROP TABLE IF EXISTS houses CASCADE;
DROP TABLE IF EXISTS ingameshop CASCADE;
DROP TABLE IF EXISTS ingameshop_log CASCADE;
DROP TABLE IF EXISTS inventory CASCADE;
DROP TABLE IF EXISTS item_cooldowns CASCADE;
DROP TABLE IF EXISTS item_stones CASCADE;
DROP TABLE IF EXISTS legion_announcement_list CASCADE;
DROP TABLE IF EXISTS legion_dominion_locations CASCADE;
DROP TABLE IF EXISTS legion_dominion_participants CASCADE;
DROP TABLE IF EXISTS legion_emblems CASCADE;
DROP TABLE IF EXISTS legion_history CASCADE;
DROP TABLE IF EXISTS legion_members CASCADE;
DROP TABLE IF EXISTS legions CASCADE;
DROP TABLE IF EXISTS mail CASCADE;
DROP TABLE IF EXISTS old_names CASCADE;
DROP TABLE IF EXISTS player_appearance CASCADE;
DROP TABLE IF EXISTS player_bind_point CASCADE;
DROP TABLE IF EXISTS player_cooldowns CASCADE;
DROP TABLE IF EXISTS player_effects CASCADE;
DROP TABLE IF EXISTS player_emotions CASCADE;
DROP TABLE IF EXISTS player_life_stats CASCADE;
DROP TABLE IF EXISTS player_macrosses CASCADE;
DROP TABLE IF EXISTS player_motions CASCADE;
DROP TABLE IF EXISTS player_npc_factions CASCADE;
DROP TABLE IF EXISTS player_passkey CASCADE;
DROP TABLE IF EXISTS player_pets CASCADE;
DROP TABLE IF EXISTS player_punishments CASCADE;
DROP TABLE IF EXISTS player_quests CASCADE;
DROP TABLE IF EXISTS player_recipes CASCADE;
DROP TABLE IF EXISTS player_registered_items CASCADE;
DROP TABLE IF EXISTS player_settings CASCADE;
DROP TABLE IF EXISTS player_skills CASCADE;
DROP TABLE IF EXISTS player_titles CASCADE;
DROP TABLE IF EXISTS player_veteran_rewards CASCADE;
DROP TABLE IF EXISTS player_web_rewards CASCADE;
DROP TABLE IF EXISTS players CASCADE;
DROP TABLE IF EXISTS portal_cooldowns CASCADE;
DROP TABLE IF EXISTS server_variables CASCADE;
DROP TABLE IF EXISTS siege_locations CASCADE;
DROP TABLE IF EXISTS surveys CASCADE;
DROP TABLE IF EXISTS towns CASCADE;

-- ----------------------------
-- Table structure for abyss_rank
-- ----------------------------
CREATE TABLE abyss_rank (
  player_id integer NOT NULL,
  daily_ap integer NOT NULL,
  weekly_ap integer NOT NULL,
  ap integer NOT NULL,
  rank smallint NOT NULL DEFAULT '1',
  max_rank smallint NOT NULL DEFAULT '1',
  rank_pos smallint NOT NULL DEFAULT '0',
  old_rank_pos smallint NOT NULL DEFAULT '0',
  daily_kill integer NOT NULL,
  weekly_kill integer NOT NULL,
  all_kill integer NOT NULL DEFAULT '0',
  last_kill integer NOT NULL,
  last_ap integer NOT NULL,
  last_update decimal(20, 0) NOT NULL,
  rank_ap integer NOT NULL DEFAULT '0',
  daily_gp integer NOT NULL DEFAULT '0',
  weekly_gp integer NOT NULL DEFAULT '0',
  gp integer NOT NULL DEFAULT '0',
  last_gp integer NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for account_passports
-- ----------------------------
CREATE TABLE account_passports (
  account_id integer NOT NULL,
  passport_id integer NOT NULL,
  rewarded integer NOT NULL,
  arrive_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (account_id, passport_id, arrive_date)
);

-- ----------------------------
-- Table structure for account_stamps
-- ----------------------------
CREATE TABLE account_stamps (
  account_id integer NOT NULL,
  stamps smallint NOT NULL DEFAULT '0',
  last_stamp timestamp NULL DEFAULT NULL,
  PRIMARY KEY (account_id)
);

-- ----------------------------
-- Table structure for advent
-- ----------------------------
CREATE TABLE advent (
  account_id integer NOT NULL,
  last_day_received date NOT NULL,
  PRIMARY KEY (account_id)
);

-- ----------------------------
-- Table structure for announcements
-- ----------------------------
CREATE TABLE announcements (
  id integer GENERATED ALWAYS AS IDENTITY,
  announce text NOT NULL,
  faction varchar(10) NOT NULL DEFAULT 'ALL',
  type varchar(10) NOT NULL DEFAULT 'SYSTEM',
  delay integer NOT NULL DEFAULT '1800',
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for blocks
-- ----------------------------
CREATE TABLE blocks (
  player integer NOT NULL,
  blocked_player integer NOT NULL,
  reason varchar(100) NOT NULL DEFAULT '',
  PRIMARY KEY (player, blocked_player)
);

-- ----------------------------
-- Table structure for bonus_packs
-- ----------------------------
CREATE TABLE bonus_packs (
  account_id integer NOT NULL,
  receiving_player integer NOT NULL,
  PRIMARY KEY (account_id)
);

-- ----------------------------
-- Table structure for bookmark
-- ----------------------------
CREATE TABLE bookmark (
  id integer GENERATED ALWAYS AS IDENTITY,
  name varchar(50) DEFAULT NULL,
  char_id integer NOT NULL,
  x float NOT NULL,
  y float NOT NULL,
  z float NOT NULL,
  world_id integer NOT NULL,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for broker
-- ----------------------------
CREATE TABLE broker (
  id integer GENERATED ALWAYS AS IDENTITY,
  item_pointer integer NOT NULL DEFAULT '0',
  item_id integer NOT NULL,
  item_count bigint NOT NULL,
  item_creator varchar(50) DEFAULT NULL,
  price bigint NOT NULL DEFAULT '0',
  broker_race varchar(10) NOT NULL,
  expire_time timestamp NOT NULL DEFAULT '2010-01-01 02:00:00',
  settle_time timestamp NOT NULL DEFAULT '2010-01-01 02:00:00',
  seller_id integer NOT NULL,
  is_sold boolean NOT NULL,
  is_settled boolean NOT NULL,
  splitting_available boolean NOT NULL DEFAULT false,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for challenge_tasks
-- ----------------------------
CREATE TABLE challenge_tasks (
  task_id integer NOT NULL,
  quest_id integer NOT NULL,
  owner_id integer NOT NULL,
  owner_type varchar(10) NOT NULL,
  complete_count integer NOT NULL DEFAULT '0',
  complete_time timestamp NULL DEFAULT NULL,
  PRIMARY KEY (task_id, quest_id, owner_id, owner_type)
);

-- ----------------------------
-- Table structure for commands_access
-- ----------------------------
CREATE TABLE commands_access (
  player_id integer NOT NULL,
  command varchar(40) NOT NULL,
  PRIMARY KEY (player_id, command)
);

-- ----------------------------
-- Table structure for craft_cooldowns
-- ----------------------------
CREATE TABLE craft_cooldowns (
  player_id integer NOT NULL,
  delay_id integer NOT NULL,
  reuse_time bigint NOT NULL,
  PRIMARY KEY (player_id, delay_id)
);

-- ----------------------------
-- Table structure for custom_instance
-- ----------------------------
CREATE TABLE custom_instance (
  player_id integer NOT NULL,
  rank integer NOT NULL,
  last_entry timestamp NOT NULL,
  max_rank integer NOT NULL,
  dps integer NOT NULL,
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for custom_instance_records
-- ----------------------------
CREATE TABLE custom_instance_records (
  player_id integer NOT NULL,
  timestamp TIMESTAMP NOT NULL,
  skill_id integer NOT NULL,
  player_class_id integer NOT NULL,
  player_hp_percentage float NOT NULL,
  player_mp_percentage float NOT NULL,
  player_is_rooted boolean NOT NULL,
  player_is_silenced boolean NOT NULL,
  player_is_bound boolean NOT NULL,
  player_is_stunned boolean NOT NULL,
  player_is_aetherhold boolean NOT NULL,
  player_buff_count integer NOT NULL,
  player_debuff_count integer NOT NULL,
  player_is_shielded boolean NOT NULL,
  target_hp_percentage float NULL,
  target_mp_percentage float NULL,
  target_focuses_player boolean NULL,
  distance float NULL,
  target_is_rooted boolean NULL,
  target_is_silenced boolean NULL,
  target_is_bound boolean NULL,
  target_is_stunned boolean NULL,
  target_is_aetherhold boolean NULL,
  target_buff_count integer NULL,
  target_debuff_count integer NULL,
  target_is_shielded boolean NULL
);

-- ----------------------------
-- Table structure for event
-- ----------------------------
CREATE TABLE event (
  event_name varchar(255) NOT NULL,
  buff_index integer NOT NULL,
  buff_active_pool_ids varchar(255) DEFAULT NULL,
  buff_allowed_days varchar(255) DEFAULT NULL,
  last_change timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (event_name, buff_index)
);

-- ----------------------------
-- Table structure for faction_packs
-- ----------------------------
CREATE TABLE faction_packs (
  account_id integer NOT NULL,
  receiving_player integer NOT NULL,
  PRIMARY KEY (account_id)
);

-- ----------------------------
-- Table structure for friends
-- ----------------------------
CREATE TABLE friends (
  player integer NOT NULL,
  friend integer NOT NULL,
  memo varchar(100) NOT NULL DEFAULT '',
  PRIMARY KEY (player, friend)
);

-- ----------------------------
-- Table structure for guides
-- ----------------------------
CREATE TABLE guides (
  guide_id integer GENERATED ALWAYS AS IDENTITY,
  player_id integer NOT NULL,
  title varchar(80) NOT NULL,
  PRIMARY KEY (guide_id)
);

-- ----------------------------
-- Table structure for headhunting
-- ----------------------------
CREATE TABLE headhunting (
  hunter_id integer NOT NULL,
  accumulated_kills integer NOT NULL,
  last_update timestamp NULL DEFAULT NULL,
  PRIMARY KEY (hunter_id)
);

-- ----------------------------
-- Table structure for house_bids
-- ----------------------------
CREATE TABLE house_bids (
  player_id integer NOT NULL,
  house_id integer NOT NULL,
  bid bigint NOT NULL,
  bid_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (player_id, house_id, bid)
);

-- ----------------------------
-- Table structure for house_object_cooldowns
-- ----------------------------
CREATE TABLE house_object_cooldowns (
  player_id integer NOT NULL,
  object_id integer NOT NULL,
  reuse_time bigint NOT NULL,
  PRIMARY KEY (player_id, object_id)
);

-- ----------------------------
-- Table structure for house_scripts
-- ----------------------------
CREATE TABLE house_scripts (
  house_id integer NOT NULL,
  script_id smallint NOT NULL,
  script text NOT NULL,
  date_added timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (house_id, script_id)
);

-- ----------------------------
-- Table structure for houses
-- ----------------------------
CREATE TABLE houses (
  id integer NOT NULL,
  player_id integer NOT NULL DEFAULT '0',
  building_id integer NOT NULL,
  address integer NOT NULL,
  acquire_time timestamp NULL DEFAULT NULL,
  settings integer NOT NULL DEFAULT '0',
  next_pay timestamp NULL DEFAULT NULL,
  sign_notice varchar(100) DEFAULT NULL,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for ingameshop
-- ----------------------------
CREATE TABLE ingameshop (
  object_id integer GENERATED ALWAYS AS IDENTITY,
  item_id integer NOT NULL,
  item_count bigint NOT NULL DEFAULT '0',
  item_price bigint NOT NULL DEFAULT '0',
  category smallint NOT NULL DEFAULT '0',
  sub_category smallint NOT NULL DEFAULT '0',
  list integer NOT NULL DEFAULT '0',
  sales_ranking integer NOT NULL DEFAULT '0',
  item_type smallint NOT NULL DEFAULT '0',
  gift boolean NOT NULL DEFAULT false,
  title_description varchar(20) NOT NULL,
  description varchar(20) NOT NULL,
  PRIMARY KEY (object_id)
);

-- ----------------------------
-- Table structure for ingameshop_log
-- ----------------------------
CREATE TABLE ingameshop_log (
  transaction_id integer GENERATED ALWAYS AS IDENTITY,
  transaction_type varchar(10) NOT NULL,
  transaction_date timestamp NULL DEFAULT NULL,
  payer_name varchar(50) NOT NULL,
  payer_account_name varchar(50) NOT NULL,
  receiver_name varchar(50) NOT NULL,
  item_id integer NOT NULL,
  item_count bigint NOT NULL DEFAULT '0',
  item_price bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (transaction_id)
);

-- ----------------------------
-- Table structure for inventory
-- ----------------------------
CREATE TABLE inventory (
  item_unique_id integer NOT NULL,
  item_id integer NOT NULL,
  item_count bigint NOT NULL DEFAULT '0',
  item_color integer DEFAULT NULL,
  color_expires integer NOT NULL DEFAULT '0',
  item_creator varchar(50) DEFAULT NULL,
  expire_time integer NOT NULL DEFAULT '0',
  activation_count integer NOT NULL DEFAULT '0',
  item_owner integer NOT NULL,
  is_equipped boolean NOT NULL DEFAULT false,
  is_soul_bound boolean NOT NULL DEFAULT false,
  slot bigint NOT NULL DEFAULT '0',
  item_location smallint DEFAULT '0',
  enchant smallint NOT NULL DEFAULT '0',
  enchant_bonus smallint NOT NULL DEFAULT '0',
  item_skin integer NOT NULL DEFAULT '0',
  fusioned_item integer NOT NULL DEFAULT '0',
  optional_socket smallint NOT NULL DEFAULT '0',
  optional_fusion_socket smallint NOT NULL DEFAULT '0',
  charge integer NOT NULL DEFAULT '0',
  tune_count smallint NOT NULL DEFAULT '0',
  rnd_bonus smallint NOT NULL DEFAULT '0',
  fusion_rnd_bonus smallint NOT NULL DEFAULT '0',
  tempering smallint NOT NULL DEFAULT '0',
  pack_count smallint NOT NULL DEFAULT '0',
  is_amplified boolean NOT NULL DEFAULT false,
  buff_skill integer NOT NULL DEFAULT '0',
  rnd_plume_bonus smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (item_unique_id)
);

-- ----------------------------
-- Table structure for item_cooldowns
-- ----------------------------
CREATE TABLE item_cooldowns (
  player_id integer NOT NULL,
  delay_id integer NOT NULL,
  use_delay integer NOT NULL,
  reuse_time bigint NOT NULL,
  PRIMARY KEY (player_id, delay_id)
);

-- ----------------------------
-- Table structure for item_stones
-- ----------------------------
CREATE TABLE item_stones (
  item_unique_id integer NOT NULL,
  item_id integer NOT NULL,
  slot integer NOT NULL,
  category integer NOT NULL DEFAULT '0',
  polishNumber integer NOT NULL,
  polishCharge integer NOT NULL,
  proc_count integer NOT NULL DEFAULT '0',
  PRIMARY KEY (item_unique_id, slot, category)
);

-- ----------------------------
-- Table structure for legion_announcement_list
-- ----------------------------
CREATE TABLE legion_announcement_list (
  legion_id integer NOT NULL,
  announcement varchar(256) NOT NULL,
  date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------
-- Table structure for legion_dominion_locations
-- ----------------------------
CREATE TABLE legion_dominion_locations (
  id integer NOT NULL DEFAULT '0',
  legion_id integer NOT NULL DEFAULT '0',
  occupied_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for legion_dominion_participants
-- ----------------------------
CREATE TABLE legion_dominion_participants (
  legion_dominion_id integer NOT NULL DEFAULT '0',
  legion_id integer NOT NULL DEFAULT '0',
  points integer NOT NULL DEFAULT '0',
  survived_time integer NOT NULL DEFAULT '0',
  participated_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (legion_id)
);

-- ----------------------------
-- Table structure for legion_emblems
-- ----------------------------
CREATE TABLE legion_emblems (
  legion_id integer NOT NULL,
  emblem_id smallint NOT NULL DEFAULT '0',
  color_a smallint NOT NULL DEFAULT '0',
  color_r smallint NOT NULL DEFAULT '0',
  color_g smallint NOT NULL DEFAULT '0',
  color_b smallint NOT NULL DEFAULT '0',
  emblem_type varchar(10) NOT NULL DEFAULT 'DEFAULT',
  emblem_data bytea,
  PRIMARY KEY (legion_id)
);

-- ----------------------------
-- Table structure for legion_history
-- ----------------------------
CREATE TABLE legion_history (
  id integer GENERATED ALWAYS AS IDENTITY,
  legion_id integer NOT NULL,
  date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  history_type varchar(20) NOT NULL,
  name varchar(50) NOT NULL,
  description varchar(30) NOT NULL DEFAULT '',
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for legion_members
-- ----------------------------
CREATE TABLE legion_members (
  legion_id integer NOT NULL,
  player_id integer NOT NULL,
  nickname varchar(10) NOT NULL DEFAULT '',
  rank varchar(20) NOT NULL DEFAULT 'VOLUNTEER',
  selfintro varchar(32) DEFAULT '',
  challenge_score integer NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for legions
-- ----------------------------
CREATE TABLE legions (
  id integer NOT NULL,
  name varchar(32) NOT NULL,
  level integer NOT NULL DEFAULT '1',
  contribution_points bigint NOT NULL DEFAULT '0',
  deputy_permission integer NOT NULL DEFAULT '7692',
  centurion_permission integer NOT NULL DEFAULT '7176',
  legionary_permission integer NOT NULL DEFAULT '6144',
  volunteer_permission integer NOT NULL DEFAULT '2048',
  disband_time integer NOT NULL DEFAULT '0',
  rank_pos smallint NOT NULL DEFAULT '0',
  old_rank_pos smallint NOT NULL DEFAULT '0',
  occupied_legion_dominion integer NOT NULL DEFAULT '0',
  last_legion_dominion integer NOT NULL DEFAULT '0',
  current_legion_dominion integer NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  CONSTRAINT name_unique UNIQUE (name)
);

-- ----------------------------
-- Table structure for mail
-- ----------------------------
CREATE TABLE mail (
  mail_unique_id integer NOT NULL,
  mail_recipient_id integer NOT NULL,
  sender_name varchar(20) NOT NULL,
  mail_title varchar(20) NOT NULL,
  mail_message varchar(1000) NOT NULL,
  unread boolean NOT NULL DEFAULT true,
  attached_item_id integer NOT NULL,
  attached_kinah_count bigint NOT NULL,
  express smallint NOT NULL DEFAULT '0',
  recieved_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (mail_unique_id)
);

-- ----------------------------
-- Table structure for old_names
-- ----------------------------
CREATE TABLE old_names (
  id integer GENERATED ALWAYS AS IDENTITY,
  player_id integer NOT NULL,
  old_name varchar(50) NOT NULL,
  new_name varchar(50) NOT NULL,
  renamed_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for player_appearance
-- ----------------------------
CREATE TABLE player_appearance (
  player_id integer NOT NULL,
  face integer NOT NULL,
  hair integer NOT NULL,
  deco integer NOT NULL,
  tattoo integer NOT NULL,
  face_contour integer NOT NULL,
  expression integer NOT NULL,
  jaw_line integer NOT NULL,
  skin_rgb integer NOT NULL,
  hair_rgb integer NOT NULL,
  lip_rgb integer NOT NULL,
  eye_rgb integer NOT NULL,
  face_shape integer NOT NULL,
  forehead integer NOT NULL,
  eye_height integer NOT NULL,
  eye_space integer NOT NULL,
  eye_width integer NOT NULL,
  eye_size integer NOT NULL,
  eye_shape integer NOT NULL,
  eye_angle integer NOT NULL,
  brow_height integer NOT NULL,
  brow_angle integer NOT NULL,
  brow_shape integer NOT NULL,
  nose integer NOT NULL,
  nose_bridge integer NOT NULL,
  nose_width integer NOT NULL,
  nose_tip integer NOT NULL,
  cheek integer NOT NULL,
  lip_height integer NOT NULL,
  mouth_size integer NOT NULL,
  lip_size integer NOT NULL,
  smile integer NOT NULL,
  lip_shape integer NOT NULL,
  jaw_height integer NOT NULL,
  chin_jut integer NOT NULL,
  ear_shape integer NOT NULL,
  head_size integer NOT NULL,
  neck integer NOT NULL,
  neck_length integer NOT NULL,
  shoulders integer NOT NULL,
  shoulder_size integer NOT NULL,
  torso integer NOT NULL,
  chest integer NOT NULL,
  waist integer NOT NULL,
  hips integer NOT NULL,
  arm_thickness integer NOT NULL,
  arm_length integer NOT NULL,
  hand_size integer NOT NULL,
  leg_thickness integer NOT NULL,
  leg_length integer NOT NULL,
  foot_size integer NOT NULL,
  facial_rate integer NOT NULL,
  voice integer NOT NULL,
  height float NOT NULL,
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for player_bind_point
-- ----------------------------
CREATE TABLE player_bind_point (
  player_id integer NOT NULL,
  map_id integer NOT NULL,
  x float NOT NULL,
  y float NOT NULL,
  z float NOT NULL,
  heading integer NOT NULL,
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for player_cooldowns
-- ----------------------------
CREATE TABLE player_cooldowns (
  player_id integer NOT NULL,
  cooldown_id integer NOT NULL,
  reuse_delay bigint NOT NULL,
  PRIMARY KEY (player_id, cooldown_id)
);

-- ----------------------------
-- Table structure for player_effects
-- ----------------------------
CREATE TABLE player_effects (
  player_id integer NOT NULL,
  skill_id integer NOT NULL,
  skill_lvl smallint NOT NULL,
  remaining_time integer NOT NULL,
  end_time bigint NOT NULL,
  force_type varchar(255) DEFAULT NULL,
  PRIMARY KEY (player_id, skill_id)
);

-- ----------------------------
-- Table structure for player_emotions
-- ----------------------------
CREATE TABLE player_emotions (
  player_id integer NOT NULL,
  emotion integer NOT NULL,
  remaining integer NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id, emotion)
);

-- ----------------------------
-- Table structure for player_life_stats
-- ----------------------------
CREATE TABLE player_life_stats (
  player_id integer NOT NULL,
  hp integer NOT NULL DEFAULT '1',
  mp integer NOT NULL DEFAULT '1',
  fp integer NOT NULL DEFAULT '1',
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for player_macrosses
-- ----------------------------
CREATE TABLE player_macrosses (
  player_id integer NOT NULL,
  "order" integer NOT NULL,
  macro text NOT NULL,
  UNIQUE (player_id, "order")
);

-- ----------------------------
-- Table structure for player_motions
-- ----------------------------
CREATE TABLE player_motions (
  player_id integer NOT NULL,
  motion_id integer NOT NULL,
  time integer NOT NULL DEFAULT '0',
  active boolean NOT NULL DEFAULT false,
  PRIMARY KEY (player_id, motion_id)
);

-- ----------------------------
-- Table structure for player_npc_factions
-- ----------------------------
CREATE TABLE player_npc_factions (
  player_id integer NOT NULL,
  faction_id integer NOT NULL,
  active boolean NOT NULL,
  time integer NOT NULL,
  state varchar(10) NOT NULL DEFAULT 'NOTING',
  quest_id integer NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id, faction_id)
);

-- ----------------------------
-- Table structure for player_passkey
-- ----------------------------
CREATE TABLE player_passkey (
  account_id integer NOT NULL,
  passkey varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (account_id, passkey)
);

-- ----------------------------
-- Table structure for player_pets
-- ----------------------------
CREATE TABLE player_pets (
  id integer NOT NULL,
  player_id integer NOT NULL,
  template_id integer NOT NULL,
  decoration integer NOT NULL,
  name varchar(255) NOT NULL,
  hungry_level smallint NOT NULL DEFAULT '0',
  feed_progress integer NOT NULL DEFAULT '0',
  reuse_time bigint NOT NULL DEFAULT '0',
  birthday timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  mood_started bigint NOT NULL DEFAULT '0',
  counter integer NOT NULL DEFAULT '0',
  mood_cd_started bigint NOT NULL DEFAULT '0',
  gift_cd_started bigint NOT NULL DEFAULT '0',
  dopings varchar(80) DEFAULT NULL,
  despawn_time timestamp NULL DEFAULT NULL,
  expire_time integer NOT NULL DEFAULT '0',
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for player_punishments
-- ----------------------------
CREATE TABLE player_punishments (
  player_id integer NOT NULL,
  punishment_type varchar(10) NOT NULL,
  start_time bigint DEFAULT '0',
  duration bigint DEFAULT '0',
  reason text,
  PRIMARY KEY (player_id, punishment_type)
);

-- ----------------------------
-- Table structure for player_quests
-- ----------------------------
CREATE TABLE player_quests (
  player_id integer NOT NULL,
  quest_id integer NOT NULL DEFAULT '0',
  status varchar(10) NOT NULL,
  quest_vars integer NOT NULL DEFAULT '0',
  flags integer NOT NULL DEFAULT '0',
  complete_count integer NOT NULL DEFAULT '0',
  next_repeat_time timestamp NULL DEFAULT NULL,
  reward smallint DEFAULT NULL,
  complete_time timestamp NULL DEFAULT NULL,
  PRIMARY KEY (player_id, quest_id)
);

-- ----------------------------
-- Table structure for player_recipes
-- ----------------------------
CREATE TABLE player_recipes (
  player_id integer NOT NULL,
  recipe_id integer NOT NULL,
  PRIMARY KEY (player_id, recipe_id)
);

-- ----------------------------
-- Table structure for player_registered_items
-- ----------------------------
CREATE TABLE player_registered_items (
  player_id integer NOT NULL,
  item_unique_id integer NOT NULL,
  item_id integer NOT NULL,
  expire_time integer DEFAULT NULL,
  color integer DEFAULT NULL,
  color_expires integer NOT NULL DEFAULT '0',
  owner_use_count integer NOT NULL DEFAULT '0',
  visitor_use_count integer NOT NULL DEFAULT '0',
  x float NOT NULL DEFAULT '0',
  y float NOT NULL DEFAULT '0',
  z float NOT NULL DEFAULT '0',
  h smallint DEFAULT NULL,
  area varchar(10) NOT NULL DEFAULT 'NONE',
  room smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id, item_unique_id, item_id),
  UNIQUE (item_unique_id)
);

-- ----------------------------
-- Table structure for player_settings
-- ----------------------------
CREATE TABLE player_settings (
  player_id integer NOT NULL,
  settings_type smallint NOT NULL,
  settings bytea NOT NULL,
  PRIMARY KEY (player_id, settings_type)
);

-- ----------------------------
-- Table structure for player_skills
-- ----------------------------
CREATE TABLE player_skills (
  player_id integer NOT NULL,
  skill_id integer NOT NULL,
  skill_level integer NOT NULL DEFAULT '1',
  PRIMARY KEY (player_id, skill_id)
);

-- ----------------------------
-- Table structure for player_titles
-- ----------------------------
CREATE TABLE player_titles (
  player_id integer NOT NULL,
  title_id integer NOT NULL,
  remaining integer NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id, title_id)
);

-- ----------------------------
-- Table structure for player_veteran_rewards
-- ----------------------------
CREATE TABLE player_veteran_rewards (
  player_id integer NOT NULL,
  received_months smallint NOT NULL DEFAULT '0',
  PRIMARY KEY (player_id)
);

-- ----------------------------
-- Table structure for player_web_rewards
-- ----------------------------
CREATE TABLE player_web_rewards (
  entry_id integer GENERATED ALWAYS AS IDENTITY,
  player_id integer NOT NULL,
  item_id integer NOT NULL,
  item_count bigint NOT NULL DEFAULT '1',
  added timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  received timestamp NULL DEFAULT NULL,
  order_id varchar(10) DEFAULT NULL,
  PRIMARY KEY (entry_id),
  UNIQUE (order_id)
);

-- ----------------------------
-- Table structure for players
-- ----------------------------
CREATE TABLE players (
  id integer NOT NULL,
  name varchar(50) NOT NULL,
  account_id integer NOT NULL,
  account_name varchar(50) NOT NULL,
  exp bigint NOT NULL DEFAULT '0',
  recoverexp bigint NOT NULL DEFAULT '0',
  old_level smallint NOT NULL DEFAULT '0',
  x float NOT NULL,
  y float NOT NULL,
  z float NOT NULL,
  heading integer NOT NULL,
  world_id integer NOT NULL,
  world_owner integer NOT NULL DEFAULT '0',
  gender varchar(10) NOT NULL,
  race varchar(10) NOT NULL,
  player_class varchar(20) NOT NULL,
  creation_date timestamp NULL DEFAULT NULL,
  deletion_date timestamp NULL DEFAULT NULL,
  last_online timestamp NULL DEFAULT NULL,
  quest_expands smallint NOT NULL DEFAULT '0',
  npc_expands smallint NOT NULL DEFAULT '0',
  item_expands smallint NOT NULL DEFAULT '0',
  wh_npc_expands smallint NOT NULL DEFAULT '0',
  wh_bonus_expands smallint NOT NULL DEFAULT '0',
  mailbox_letters smallint NOT NULL DEFAULT '0',
  title_id integer NOT NULL DEFAULT '-1',
  bonus_title_id integer NOT NULL DEFAULT '-1',
  dp integer NOT NULL DEFAULT '0',
  soul_sickness smallint NOT NULL DEFAULT '0',
  reposte_energy bigint NOT NULL DEFAULT '0',
  online boolean NOT NULL DEFAULT false,
  note text,
  mentor_flag_time integer NOT NULL DEFAULT '0',
  last_transfer_time decimal(20,0) NOT NULL DEFAULT '0',
  luck_value float NOT NULL DEFAULT 0.5,
  PRIMARY KEY (id),
  CONSTRAINT players_name_unique UNIQUE (name)
);

-- ----------------------------
-- Table structure for portal_cooldowns
-- ----------------------------
CREATE TABLE portal_cooldowns (
  player_id integer NOT NULL,
  world_id integer NOT NULL,
  reuse_time bigint NOT NULL,
  entry_count integer NOT NULL,
  PRIMARY KEY (player_id, world_id)
);

-- ----------------------------
-- Table structure for server_variables
-- ----------------------------
CREATE TABLE server_variables (
  key varchar(30) NOT NULL,
  value varchar(30) NOT NULL,
  PRIMARY KEY (key)
);

-- ----------------------------
-- Table structure for siege_locations
-- ----------------------------
CREATE TABLE siege_locations (
  id integer NOT NULL,
  race varchar(10) NOT NULL,
  legion_id integer NOT NULL,
  occupy_count smallint NOT NULL DEFAULT '0',
  faction_balance smallint NOT NULL DEFAULT '0',
  -- Solo-ownership extension (单人要塞): 0 if unowned by any individual player.
  owner_player_id integer NOT NULL DEFAULT 0,
  -- Capture wall-clock (epoch ms). Used for inactivity decay.
  owner_captured_at bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for surveys
-- ----------------------------
CREATE TABLE surveys (
  unique_id integer GENERATED ALWAYS AS IDENTITY,
  owner_id integer NOT NULL,
  item_id integer NOT NULL,
  item_count decimal(20,0) NOT NULL DEFAULT '1',
  html_text text NOT NULL,
  html_radio varchar(100) NOT NULL DEFAULT 'accept',
  used boolean NOT NULL DEFAULT false,
  used_time varchar(100) NOT NULL DEFAULT '',
  PRIMARY KEY (unique_id)
);

-- ----------------------------
-- Table structure for towns
-- ----------------------------
CREATE TABLE towns (
  id integer NOT NULL,
  level integer NOT NULL DEFAULT '1',
  points integer NOT NULL DEFAULT '0',
  race varchar(10) NOT NULL,
  level_up_date timestamp NOT NULL DEFAULT '1970-01-01 07:00:01',
  PRIMARY KEY (id)
);

-- ----------------------------
-- Indexes
-- ----------------------------
CREATE INDEX idx_abyss_rank_rank ON abyss_rank (rank);
CREATE INDEX idx_abyss_rank_rank_pos ON abyss_rank (rank_pos);
CREATE INDEX idx_abyss_rank_gp ON abyss_rank (gp);
CREATE INDEX idx_blocks_blocked_player ON blocks (blocked_player);
CREATE INDEX idx_broker_seller_id ON broker (seller_id);
CREATE INDEX idx_custom_instance_rank ON custom_instance (rank);
CREATE INDEX idx_custom_instance_last_entry ON custom_instance (last_entry);
CREATE INDEX idx_friends_friend ON friends (friend);
CREATE INDEX idx_guides_player_id ON guides (player_id);
CREATE INDEX idx_house_bids_house_id ON house_bids (house_id);
CREATE INDEX idx_houses_address ON houses (address);
CREATE INDEX idx_inventory_item_location ON inventory (item_location);
CREATE INDEX idx_inventory_owner_loc_equip ON inventory (item_owner, item_location, is_equipped);
CREATE INDEX idx_legion_announcement_list_legion_id ON legion_announcement_list (legion_id);
CREATE INDEX idx_legion_history_legion_id ON legion_history (legion_id);
CREATE INDEX idx_legion_members_player_id ON legion_members (player_id);
CREATE INDEX idx_legion_members_legion_id ON legion_members (legion_id);
CREATE INDEX idx_legions_rank_pos ON legions (rank_pos);
CREATE INDEX idx_mail_recipient_id ON mail (mail_recipient_id);
CREATE INDEX idx_old_names_player_id ON old_names (player_id);
CREATE INDEX idx_old_names_renamed_date ON old_names (renamed_date);
CREATE INDEX idx_player_web_rewards_player_id ON player_web_rewards (player_id);
CREATE INDEX idx_players_account_id ON players (account_id);
CREATE INDEX idx_surveys_owner_id ON surveys (owner_id);

-- ----------------------------
-- Foreign key constraints
-- ----------------------------
ALTER TABLE abyss_rank ADD CONSTRAINT abyss_rank_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE blocks ADD CONSTRAINT blocks_ibfk_1 FOREIGN KEY (player) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE blocks ADD CONSTRAINT blocks_ibfk_2 FOREIGN KEY (blocked_player) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE broker ADD CONSTRAINT broker_ibfk_1 FOREIGN KEY (seller_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE commands_access ADD CONSTRAINT commands_access_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE craft_cooldowns ADD CONSTRAINT craft_cooldowns_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE custom_instance ADD CONSTRAINT custom_instance_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE custom_instance_records ADD CONSTRAINT custom_instance_records_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE friends ADD CONSTRAINT friends_ibfk_1 FOREIGN KEY (player) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE friends ADD CONSTRAINT friends_ibfk_2 FOREIGN KEY (friend) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE guides ADD CONSTRAINT guides_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE house_bids ADD CONSTRAINT house_id_ibfk_1 FOREIGN KEY (house_id) REFERENCES houses (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE house_object_cooldowns ADD CONSTRAINT house_object_cooldowns_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE house_object_cooldowns ADD CONSTRAINT house_object_cooldowns_ibfk_2 FOREIGN KEY (object_id) REFERENCES player_registered_items (item_unique_id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE house_scripts ADD CONSTRAINT houses_id_ibfk_1 FOREIGN KEY (house_id) REFERENCES houses (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE item_cooldowns ADD CONSTRAINT item_cooldowns_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE item_stones ADD CONSTRAINT item_stones_ibfk_1 FOREIGN KEY (item_unique_id) REFERENCES inventory (item_unique_id) ON DELETE CASCADE;
ALTER TABLE legion_announcement_list ADD CONSTRAINT legion_announcement_list_ibfk_1 FOREIGN KEY (legion_id) REFERENCES legions (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE legion_emblems ADD CONSTRAINT legion_emblems_ibfk_1 FOREIGN KEY (legion_id) REFERENCES legions (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE legion_history ADD CONSTRAINT legion_history_ibfk_1 FOREIGN KEY (legion_id) REFERENCES legions (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE legion_members ADD CONSTRAINT legion_members_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE legion_members ADD CONSTRAINT legion_members_ibfk_2 FOREIGN KEY (legion_id) REFERENCES legions (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE mail ADD CONSTRAINT FK_mail FOREIGN KEY (mail_recipient_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE old_names ADD CONSTRAINT old_names_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE player_appearance ADD CONSTRAINT player_id_fk FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_bind_point ADD CONSTRAINT player_bind_point_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE player_cooldowns ADD CONSTRAINT player_cooldowns_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE player_effects ADD CONSTRAINT player_effects_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_emotions ADD CONSTRAINT player_emotions_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_life_stats ADD CONSTRAINT FK_player_life_stats FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE player_macrosses ADD CONSTRAINT player_macrosses_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_motions ADD CONSTRAINT motions_player_id_fk FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_npc_factions ADD CONSTRAINT player_npc_factions_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_pets ADD CONSTRAINT FK_player_pets FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE player_punishments ADD CONSTRAINT player_punishments_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_quests ADD CONSTRAINT player_quests_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_recipes ADD CONSTRAINT player_recipes_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_registered_items ADD CONSTRAINT player_regitems_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_settings ADD CONSTRAINT ps_pl_fk FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_skills ADD CONSTRAINT player_skills_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_titles ADD CONSTRAINT player_titles_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_veteran_rewards ADD CONSTRAINT player_veteran_rewards_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE player_web_rewards ADD CONSTRAINT player_web_rewards_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE;
ALTER TABLE portal_cooldowns ADD CONSTRAINT portal_cooldowns_ibfk_1 FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE surveys ADD CONSTRAINT surveys_ibfk_1 FOREIGN KEY (owner_id) REFERENCES players (id) ON DELETE CASCADE ON UPDATE CASCADE;

-- ----------------------------
-- Auto-update timestamp triggers (replaces ON UPDATE CURRENT_TIMESTAMP)
-- ----------------------------
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  IF TG_ARGV[0] = 'last_change' THEN
    NEW.last_change = CURRENT_TIMESTAMP;
  ELSIF TG_ARGV[0] = 'occupied_date' THEN
    NEW.occupied_date = CURRENT_TIMESTAMP;
  ELSIF TG_ARGV[0] = 'participated_date' THEN
    NEW.participated_date = CURRENT_TIMESTAMP;
  ELSIF TG_ARGV[0] = 'recieved_time' THEN
    NEW.recieved_time = CURRENT_TIMESTAMP;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_last_change
  BEFORE UPDATE ON event
  FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at('last_change');

CREATE TRIGGER trg_legion_dominion_locations_occupied_date
  BEFORE UPDATE ON legion_dominion_locations
  FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at('occupied_date');

CREATE TRIGGER trg_legion_dominion_participants_participated_date
  BEFORE UPDATE ON legion_dominion_participants
  FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at('participated_date');

CREATE TRIGGER trg_mail_recieved_time
  BEFORE UPDATE ON mail
  FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at('recieved_time');
