package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.model.house.HouseBids;
import com.aionemu.gameserver.model.house.HouseBids.Bid;

/**
 * @author Rolandas
 */
public class HouseBidsDAO {

	private static final Logger log = LoggerFactory.getLogger(HouseBidsDAO.class);

	public static final String LOAD_QUERY = "SELECT b.*, CASE WHEN b.player_id = p.id THEN 1 ELSE 0 END playerExists FROM house_bids b LEFT JOIN players p ON p.id = b.player_id ORDER BY bid, bid_time";
	public static final String INSERT_QUERY = "INSERT INTO house_bids (player_id, house_id, bid, bid_time) VALUES (?, ?, ?, ?)";
	public static final String DELETE_QUERY = "DELETE FROM house_bids WHERE house_id = ?";
	public static final String DELETE_SINGLE_BID_QUERY = "DELETE FROM house_bids WHERE player_id = ? AND house_id = ? AND bid = ?";
	public static final String DISABLE_QUERY = "UPDATE house_bids SET player_id = 0 WHERE player_id = ?";

	public static Set<Integer> loadBids(Map<Integer, HouseBids> houseBidsMap) {
		Set<Integer> missingPlayers = new HashSet<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(LOAD_QUERY)) {
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				int playerId = rs.getInt("player_id");
				int houseId = rs.getInt("house_id");
				long bid = rs.getLong("bid");
				long bidTime = rs.getTimestamp("bid_time").getTime();
				HouseBids houseBids = houseBidsMap.get(houseId);
				if (houseBids == null) {
					houseBids = new HouseBids(houseId, bid, bidTime);
					houseBidsMap.put(houseId, houseBids);
				} else {
					houseBids.bid(playerId, bid, bidTime);
					if (playerId != 0 && !rs.getBoolean("playerExists"))
						missingPlayers.add(houseId);
				}
			}
			rs.close();
		} catch (Exception e) {
			log.error("Cannot read house bids", e);
		}
		return missingPlayers;
	}

	public static boolean addBid(Bid bid) {
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT_QUERY)) {
			ps.setInt(1, bid.getPlayerObjectId());
			ps.setInt(2, bid.getHouseObjectId());
			ps.setLong(3, bid.getKinah());
			ps.setTimestamp(4, new Timestamp(bid.getTime()));
			ps.execute();
			return true;
		} catch (Exception e) {
			log.error("Cannot insert house bid", e);
			return false;
		}
	}

	public static boolean deleteOrDisableBids(int playerId, List<Bid> bids) {
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement deleteSingle = con.prepareStatement(DELETE_SINGLE_BID_QUERY);
			PreparedStatement disable = con.prepareStatement(DISABLE_QUERY)) {
			for (Bid bid : bids) {
				deleteSingle.setInt(1, bid.getPlayerObjectId());
				deleteSingle.setInt(2, bid.getHouseObjectId());
				deleteSingle.setLong(3, bid.getKinah());
				deleteSingle.execute();
			}
			disable.setInt(1, playerId);
			disable.execute();
			return true;
		} catch (Exception e) {
			log.error("Cannot delete or disable house bids for player " + playerId, e);
			return false;
		}
	}

	public static boolean deleteHouseBids(int houseId) {
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(DELETE_QUERY)) {
			ps.setInt(1, houseId);
			ps.execute();
			return true;
		} catch (Exception e) {
			log.error("Cannot delete house bids", e);
			return false;
		}
	}

}
