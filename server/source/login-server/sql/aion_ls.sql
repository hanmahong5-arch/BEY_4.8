-- ----------------------------
-- account_data
-- ----------------------------
DROP TABLE IF EXISTS account_data;
CREATE TABLE account_data (
  id integer GENERATED ALWAYS AS IDENTITY,
  name varchar(45) DEFAULT NULL,
  ext_auth_name varchar(45) DEFAULT NULL,
  password varchar(65) NOT NULL,
  creation_date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  activated boolean NOT NULL DEFAULT TRUE,
  access_level smallint NOT NULL DEFAULT '0',
  membership smallint NOT NULL DEFAULT '0',
  old_membership smallint NOT NULL DEFAULT '0',
  last_server smallint NOT NULL DEFAULT '-1',
  last_ip varchar(20) DEFAULT NULL,
  last_mac varchar(20) NOT NULL DEFAULT 'xx-xx-xx-xx-xx-xx',
  last_hdd_serial varchar(100) DEFAULT NULL,
  allowed_hdd_serial varchar(100) DEFAULT NULL,
  ip_force varchar(20) DEFAULT NULL,
  expire date DEFAULT NULL,
  toll bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (id),
  UNIQUE (name),
  UNIQUE (ext_auth_name)
);

-- ----------------------------
-- account_time
-- ----------------------------
DROP TABLE IF EXISTS account_time;
CREATE TABLE account_time (
  account_id integer NOT NULL,
  last_active timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expiration_time timestamp NULL DEFAULT NULL,
  session_duration integer DEFAULT '0',
  accumulated_online integer DEFAULT '0',
  accumulated_rest integer DEFAULT '0',
  penalty_end timestamp NULL DEFAULT NULL,
  PRIMARY KEY (account_id)
);

-- ----------------------------
-- Table structure for account_rewards
-- ----------------------------
DROP TABLE IF EXISTS account_rewards;
CREATE TABLE account_rewards (
  uniqId integer GENERATED ALWAYS AS IDENTITY,
  accountId integer NOT NULL,
  added varchar(70) NOT NULL DEFAULT '',
  points decimal(20,0) NOT NULL DEFAULT '0',
  received varchar(70) NOT NULL DEFAULT '0',
  rewarded boolean NOT NULL DEFAULT false,
  PRIMARY KEY (uniqId)
);

-- ----------------------------
-- banned_ip
-- ----------------------------
DROP TABLE IF EXISTS banned_ip;
CREATE TABLE banned_ip (
  id integer GENERATED ALWAYS AS IDENTITY,
  mask varchar(45) NOT NULL,
  time_end timestamp NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE (mask)
);

-- ----------------------------
-- gameservers
-- ----------------------------
DROP TABLE IF EXISTS gameservers;
CREATE TABLE gameservers (
  id integer GENERATED ALWAYS AS IDENTITY,
  mask varchar(45) NOT NULL,
  password varchar(65) NOT NULL,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for banned_mac
-- ----------------------------
DROP TABLE IF EXISTS banned_mac;
CREATE TABLE banned_mac (
  uniId integer GENERATED ALWAYS AS IDENTITY,
  address varchar(20) NOT NULL,
  time timestamp NOT NULL,
  details varchar(255) NOT NULL DEFAULT '',
  PRIMARY KEY (uniId)
);

-- ----------------------------
-- Table structure for player_transfers
-- ----------------------------
DROP TABLE IF EXISTS player_transfers;
CREATE TABLE player_transfers (
  id integer GENERATED ALWAYS AS IDENTITY,
  source_server smallint NOT NULL,
  target_server smallint NOT NULL,
  source_account_id integer NOT NULL,
  target_account_id integer NOT NULL,
  player_id integer NOT NULL,
  status smallint NOT NULL DEFAULT '0',
  time_added varchar(100) DEFAULT NULL,
  time_performed varchar(100) DEFAULT NULL,
  time_done varchar(100) DEFAULT NULL,
  comment text,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for banned_hdd
-- ----------------------------
DROP TABLE IF EXISTS banned_hdd;
CREATE TABLE banned_hdd (
  id integer GENERATED ALWAYS AS IDENTITY,
  serial varchar(100) NOT NULL,
  time timestamp NOT NULL,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for account_login_history
-- ----------------------------
DROP TABLE IF EXISTS account_login_history;
CREATE TABLE account_login_history (
  account_id integer NOT NULL,
  gameserver_id smallint NOT NULL,
  date timestamp NOT NULL,
  ip varchar(20) DEFAULT NULL,
  mac varchar(20) DEFAULT NULL,
  hdd_serial varchar(100) DEFAULT NULL,
  PRIMARY KEY (account_id, date)
);
