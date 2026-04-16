package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;

/**
 * Data access for epoch heirlooms — the per-epoch legendary items whose entire
 * ownership history is permanently tracked.
 */
public class HeirloomDAO {

    private static final Logger log = LoggerFactory.getLogger(HeirloomDAO.class);

    // -- Quota management ------------------------------------------------------

    /**
     * Returns how many heirloom items have already been registered this epoch.
     * The cap is enforced by the caller (HeirloomService).
     */
    public static int countDroppedThisEpoch(int epochId) {
        String sql = "SELECT COUNT(*) FROM epoch_heirloom WHERE epoch_id = ?";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, epochId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to count epoch heirlooms for epoch {}", epochId, e);
        }
        return 0;
    }

    /** Returns true if the given item object is already registered as an heirloom. */
    public static boolean isHeirloom(long itemObjId) {
        String sql = "SELECT 1 FROM epoch_heirloom WHERE item_obj_id = ?";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, itemObjId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check heirloom status for obj {}", itemObjId, e);
        }
        return false;
    }

    // -- Registration ----------------------------------------------------------

    /**
     * Registers a newly dropped heirloom item.
     *
     * @param itemObjId      unique object ID of the item instance
     * @param epochId        current epoch
     * @param templateId     item template ID (for display name lookup)
     * @param fromNpcId      NPC that dropped it, 0 if unknown
     * @param firstOwner     player name who picked it up
     */
    public static void registerHeirloom(long itemObjId, int epochId, int templateId,
            int fromNpcId, String firstOwner) {
        String sql = "INSERT INTO epoch_heirloom "
            + "(item_obj_id, epoch_id, item_template_id, dropped_from_npc_id, first_owner, current_owner) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, itemObjId);
            ps.setInt(2, epochId);
            ps.setInt(3, templateId);
            ps.setInt(4, fromNpcId);
            ps.setString(5, firstOwner);
            ps.setString(6, firstOwner);
            ps.execute();
        } catch (SQLException e) {
            log.error("Failed to register heirloom obj={} owner={}", itemObjId, firstOwner, e);
        }

        // Record initial drop in transfer history (from_player = null = original drop)
        insertTransferHistory(itemObjId, null, firstOwner);
    }

    // -- Ownership transfer ----------------------------------------------------

    /**
     * Updates the current_owner field and increments the transfer count.
     * Called whenever an heirloom changes hands via trade or drop.
     *
     * @param itemObjId  the heirloom item's object ID
     * @param fromPlayer previous owner name
     * @param toPlayer   new owner name
     */
    public static void updateOwner(long itemObjId, String fromPlayer, String toPlayer) {
        String sql = "UPDATE epoch_heirloom "
            + "SET current_owner = ?, transfer_count = transfer_count + 1 "
            + "WHERE item_obj_id = ?";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, toPlayer);
            ps.setLong(2, itemObjId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update heirloom owner obj={}", itemObjId, e);
        }

        insertTransferHistory(itemObjId, fromPlayer, toPlayer);
    }

    /** Returns the current owner's name for a given heirloom, or null if not found. */
    public static String getCurrentOwner(long itemObjId) {
        String sql = "SELECT current_owner FROM epoch_heirloom WHERE item_obj_id = ?";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, itemObjId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getString(1);
            }
        } catch (SQLException e) {
            log.error("Failed to query heirloom owner obj={}", itemObjId, e);
        }
        return null;
    }

    // -- Private ---------------------------------------------------------------

    private static void insertTransferHistory(long itemObjId, String from, String to) {
        String sql = "INSERT INTO heirloom_history (item_obj_id, from_player, to_player) VALUES (?, ?, ?)";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, itemObjId);
            ps.setString(2, from);  // null is valid (original drop)
            ps.setString(3, to);
            ps.execute();
        } catch (SQLException e) {
            log.error("Failed to insert heirloom history obj={} {}→{}", itemObjId, from, to, e);
        }
    }
}
