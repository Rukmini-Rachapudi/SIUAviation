package com.skidreport.db;

import com.skidreport.model.NearMissEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * NearMissEventDao
 *
 * All insert and fetch operations on the near_miss_events table.
 */
public class NearMissEventDao {

    private static final String INSERT_SQL =
        "INSERT INTO near_miss_events " +
        "  (local_date, local_time, tail1, tail2, " +
        "   latitude1, longitude1, alt1, ias1, " +
        "   latitude2, longitude2, alt2, ias2, " +
        "   distance_ft, year_month) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    // ------------------------------------------------------------------------
    // INSERT: batch insert a list of NearMissEvent rows
    // ------------------------------------------------------------------------
    public static void insertBatch(Connection conn,
                                   List<NearMissEvent> events) throws Exception {
        if (events.isEmpty()) return;

        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            int count = 0;
            for (NearMissEvent ev : events) {
                ps.setString(1,  ev.date);
                ps.setString(2,  ev.time);
                ps.setString(3,  ev.tail1);
                ps.setString(4,  ev.tail2);
                ps.setDouble(5,  ev.lat1);
                ps.setDouble(6,  ev.lon1);
                ps.setDouble(7,  ev.alt1);
                ps.setDouble(8,  ev.ias1);
                ps.setDouble(9,  ev.lat2);
                ps.setDouble(10, ev.lon2);
                ps.setDouble(11, ev.alt2);
                ps.setDouble(12, ev.ias2);
                ps.setDouble(13, ev.distanceFt);
                ps.setString(14, ev.yearMonth);
                ps.addBatch();
                count++;
                if (count % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------------
    // FETCH: all unique (tail1, tail2, year_month) combinations
    // Used to determine which Excel files to write
    // ------------------------------------------------------------------------
    public static List<String[]> getAllPairsAndMonths(Connection conn) throws Exception {
        List<String[]> pairs = new ArrayList<>();
        String sql =
            "SELECT DISTINCT tail1, tail2, year_month " +
            "FROM near_miss_events " +
            "ORDER BY tail1, tail2, year_month";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                pairs.add(new String[]{
                        rs.getString("tail1"),
                        rs.getString("tail2"),
                        rs.getString("year_month")
                });
            }
        }
        return pairs;
    }

    // ------------------------------------------------------------------------
    // FETCH: all events for a specific pair and month
    // Used by Excel writer to populate one report file
    // ------------------------------------------------------------------------
    public static List<NearMissEvent> getEventsForPairAndMonth(
            Connection conn, String tail1, String tail2, String yearMonth)
            throws Exception {

        String sql =
            "SELECT local_date, local_time, " +
            "       latitude1, longitude1, alt1, ias1, " +
            "       latitude2, longitude2, alt2, ias2, distance_ft " +
            "FROM near_miss_events " +
            "WHERE tail1 = ? AND tail2 = ? AND year_month = ? " +
            "ORDER BY local_date, local_time";

        List<NearMissEvent> events = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tail1);
            ps.setString(2, tail2);
            ps.setString(3, yearMonth);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    NearMissEvent ev = new NearMissEvent();
                    ev.date       = rs.getString("local_date");
                    ev.time       = rs.getString("local_time");
                    ev.tail1      = tail1;
                    ev.tail2      = tail2;
                    ev.lat1       = rs.getDouble("latitude1");
                    ev.lon1       = rs.getDouble("longitude1");
                    ev.alt1       = rs.getDouble("alt1");
                    ev.ias1       = rs.getDouble("ias1");
                    ev.lat2       = rs.getDouble("latitude2");
                    ev.lon2       = rs.getDouble("longitude2");
                    ev.alt2       = rs.getDouble("alt2");
                    ev.ias2       = rs.getDouble("ias2");
                    ev.distanceFt = rs.getDouble("distance_ft");
                    ev.yearMonth  = yearMonth;
                    events.add(ev);
                }
            }
        }
        return events;
    }
}
