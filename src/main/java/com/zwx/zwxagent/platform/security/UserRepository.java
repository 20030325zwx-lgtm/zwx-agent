package com.zwx.zwxagent.platform.security;

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

    public Optional<AppUser> findById(String id) {
        List<AppUser> users = jdbcTemplate.query("""
                SELECT id, tenant_id, username, password_hash, role, status
                FROM app_user WHERE id = ?
                """, (rs, rowNum) -> new AppUser(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.from(rs.getString("role")),
                rs.getString("status")), id);
        return users.stream().findFirst();
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

    public void updateRole(String userId, Role role) {
        jdbcTemplate.update("UPDATE app_user SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", role.name(), userId);
    }

    /** 租户内账号列表（管理员用户管理用）。 */
    public List<AppUser> findByTenant(String tenantId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, username, password_hash, role, status
                FROM app_user WHERE tenant_id = ?
                ORDER BY created_at, username
                """, (rs, rowNum) -> new AppUser(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.from(rs.getString("role")),
                rs.getString("status")), tenantId);
    }

    /** 按租户删除账号；返回是否命中，避免跨租户删除。 */
    public boolean deleteById(String userId, String tenantId) {
        return jdbcTemplate.update("DELETE FROM app_user WHERE id = ? AND tenant_id = ?", userId, tenantId) > 0;
    }

    public long countByTenantAndRole(String tenantId, Role role) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE tenant_id = ? AND role = ?", Long.class, tenantId, role.name());
        return count == null ? 0 : count;
    }
}
