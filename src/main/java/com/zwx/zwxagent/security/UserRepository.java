package com.zwx.zwxagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return count == null ? 0 : count;
    }

    public Optional<AppUser> findByUsername(String username) {
        List<AppUser> users = jdbcTemplate.query("""
                SELECT id, tenant_id, username, password_hash, role, status
                FROM app_user WHERE username = ?
                """, (rs, rowNum) -> new AppUser(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.from(rs.getString("role")),
                rs.getString("status")), username);
        return users.stream().findFirst();
    }

    public AppUser insert(String tenantId, String username, String passwordHash, Role role) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO app_user (id, tenant_id, username, password_hash, role, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, tenantId, username, passwordHash, role.name());
        return new AppUser(id, tenantId, username, passwordHash, role, "ACTIVE");
    }
}
