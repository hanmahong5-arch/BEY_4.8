package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.commons.database.DatabaseFactory;

/**
 * Data access for the world chronicle (permanent event history) and epoch metadata.
 * All writes are fire-and-forget; failures are logged but never bubble up to callers,
 * so chronicle outages never affect core game logic.
 */
public class ChronicleDAO {

    private static final Logger log = LoggerFactory.getLogger(ChronicleDAO.class);

    // -- Epoch ------------------------------------------------------------------

    /** Returns the ID of the currently active (un-ended) epoch, defaulting to 1. */
    public static int getCurrentEpochId() {
        String sql = "SELECT id FROM epoch WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next())
                return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Failed to query current epoch", e);
        }
        return 1;
    }

    /** Returns the display title of the current epoch. */
    public static String getCurrentEpochTitle() {
        String sql = "SELECT title FROM epoch WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next())
                return rs.getString(1);
        } catch (SQLException e) {
            log.error("Failed to query epoch title", e);
        }
        return "第一纪元·拾光初醒";
    }

    // -- Chronicle events -------------------------------------------------------

    /**
     * Persists one world event to the chronicle.
     *
     * @param epochId      current epoch ID
     * @param eventType    e.g. SIEGE_CAPTURE, HEIRLOOM_DROP
     * @param faction      "ELYOS", "ASMO", "BALAUR" or null
     * @param locationId   fortress/map ID, 0 if N/A
     * @param locationName human-readable location name
     * @param protagonist  player name, legion name, or null
     * @param title        short headline shown in .chronicle output
     * @param narrative    full flavour text (may be null)
     * @param importance   1 (legendary) – 5 (minor)
     */
    public static void insertEvent(int epochId, String eventType, String faction,
            int locationId, String locationName, String protagonist,
            String title, String narrative, int importance) {
        String sql = "INSERT INTO world_chronicle "
            + "(epoch_id, event_type, faction, location_id, location_name, protagonist, title, narrative, importance) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, epochId);
            ps.setString(2, eventType);
            ps.setString(3, faction);
            ps.setInt(4, locationId);
            ps.setString(5, locationName != null ? locationName : "");
            ps.setString(6, protagonist);
            ps.setString(7, title);
            ps.setString(8, narrative);
            ps.setInt(9, importance);
            ps.execute();
        } catch (SQLException e) {
            log.error("Failed to insert chronicle event type={} protagonist={}", eventType, protagonist, e);
        }
    }

    /**
     * Returns the most recent {@code limit} chronicle headlines for the .chronicle command.
     * Each entry is a pre-formatted string: "[YYYY-MM-DD] headline"
     */
    public static List<String> getRecentEvents(int limit) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT occurred_at, title FROM world_chronicle ORDER BY occurred_at DESC LIMIT ?";
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String date = rs.getTimestamp(1).toLocalDateTime().toLocalDate().toString();
                    result.add("[" + date + "] " + rs.getString(2));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query recent chronicle events", e);
        }
        return result;
    }
}
