package com.seft.learn.example.service.export;

import java.sql.ResultSet;
import java.sql.SQLException;

public class CsvFormatter {

    private static final String[] HEADERS = {"id", "email", "name", "created_at"};

    public String formatHeader() {
        return formatRow(HEADERS);
    }

    public String formatRow(ResultSet rs) throws SQLException {
        return formatRow(
            escapeCsv(rs.getString("id")),
            escapeCsv(rs.getString("email")),
            escapeCsv(rs.getString("name")),
            escapeCsv(rs.getString("created_at"))
        );
    }

    private String formatRow(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(values[i]).append("\"");
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
}
