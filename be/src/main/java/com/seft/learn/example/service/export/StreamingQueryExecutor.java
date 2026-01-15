package com.seft.learn.example.service.export;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class StreamingQueryExecutor {

    private final JdbcTemplate jdbcTemplate;

    private static final int DEFAULT_FETCH_SIZE = 10000;

    public void streamUsers(Consumer<ResultSet> rowHandler) {
        streamUsers(rowHandler, DEFAULT_FETCH_SIZE);
    }

    public void streamUsers(Consumer<ResultSet> rowHandler, int fetchSize) {
        String sql = "SELECT id, email, name, created_at FROM users";
        
        jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(
                sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            );
            ps.setFetchSize(fetchSize);
            return ps;
        }, rs -> {
            rowHandler.accept(rs);
        });
    }

    public long countUsers() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class);
        return count != null ? count : 0L;
    }
}
