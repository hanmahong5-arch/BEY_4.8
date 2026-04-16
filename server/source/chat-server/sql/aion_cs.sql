-- ----------------------------
-- Table structure for chatlog
-- ----------------------------
DROP TABLE IF EXISTS chatlog;
CREATE TABLE chatlog (
  id integer GENERATED ALWAYS AS IDENTITY,
  sender varchar(255) DEFAULT NULL,
  message text NOT NULL,
  type varchar(255) DEFAULT NULL,
  PRIMARY KEY (id)
);
