package com.autoapply.util;

import java.sql.*;

public class
DB {
private final String url;

    public DB(String fileName){
        this.url = "jdbc:sqlite:" + fileName;
        init();
    }

    private void init(){
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement st = conn.createStatement()){
                st.executeUpdate("CREATE TABLE IF NOT EXISTS applications (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "site TEXT, job_id TEXT UNIQUE, title TEXT, company TEXT, location TEXT," +
                        "status TEXT, reason TEXT, ts DATETIME DEFAULT CURRENT_TIMESTAMP)");
            }
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    public boolean exists(String jobId) {
        String sql = "SELECT 1 FROM applications WHERE job_id=? AND status IN ('applied','skipped')";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            System.err.println("DB error: " + e.getMessage());
            return false;
        }
    }

    // Fetch {job_id, title, company} for every row with the given status — used
    // by the VerifyApplied cleanup tool to re-check past applications.
    public java.util.List<String[]> fetchByStatus(String status){
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String sql = "SELECT job_id, title, company FROM applications WHERE status=? ORDER BY ts DESC";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)){
            ps.setString(1, status);
            try (ResultSet r = ps.executeQuery()){
                while (r.next()) out.add(new String[]{ r.getString(1), r.getString(2), r.getString(3) });
            }
        } catch (SQLException e){
            System.err.println("DB error: " + e.getMessage());
        }
        return out;
    }

    // Update status/reason for an existing job_id (cleanup correction).
    public void setStatus(String jobId, String status, String reason){
        String sql = "UPDATE applications SET status=?, reason=? WHERE job_id=?";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)){
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setString(3, jobId);
            ps.executeUpdate();
        } catch (SQLException e){
            System.err.println("DB error: " + e.getMessage());
        }
    }

    public void upsert(String site, String jobId, String title, String company, String location, String status, String reason){
        String sql = "INSERT INTO applications(site,job_id,title,company,location,status,reason) VALUES(?,?,?,?,?,?,?) " +
                "ON CONFLICT(job_id) DO UPDATE SET status=excluded.status, reason=excluded.reason";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(sql)){
            ps.setString(1, site);
            ps.setString(2, jobId);
            ps.setString(3, title);
            ps.setString(4, company);
            ps.setString(5, location);
            ps.setString(6, status);
            ps.setString(7, reason);
            ps.executeUpdate();
        } catch (SQLException e){
            System.err.println("DB error: " + e.getMessage());
        }
    }
}
