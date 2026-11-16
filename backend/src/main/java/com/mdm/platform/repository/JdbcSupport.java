package com.mdm.platform.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 通用只读/原生 SQL 支撑，封装 JSONB 转换与命名查询。
 */
@Repository
public class JdbcSupport {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String sql, Object... args) {
        Long c = jdbc.queryForObject(sql, Long.class, args);
        return c == null ? 0 : c;
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /**
     * 构造 PostgreSQL bigint[] 字面量（如 "{1,2,3}"），SQL 中用 ?::bigint[] 绑定，
     * 避免手动获取/释放连接导致的池泄漏。
     */
    public String pgLongArrayLiteral(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + values.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    /**
     * 读取 bigint[] 列：JDBC 返回 java.sql.Array，统一转 Long 列表。
     */
    @SuppressWarnings("unchecked")
    public List<Long> toLongList(Object column) {
        if (column == null) {
            return List.of();
        }
        try {
            Object value = column;
            if (column instanceof Array arr) {
                value = arr.getArray();
            }
            if (value instanceof Object[] objects) {
                List<Long> result = new java.util.ArrayList<>(objects.length);
                for (Object o : objects) {
                    if (o != null) {
                        result.add(((Number) o).longValue());
                    }
                }
                return result;
            }
            return List.of();
        } catch (SQLException e) {
            throw new IllegalStateException("读取 PG 数组失败", e);
        }
    }

    /**
     * timestamptz 列经 JdbcTemplate 可能返回 Timestamp / OffsetDateTime，统一转换。
     */
    public OffsetDateTime toOffsetDateTime(Object column) {
        if (column == null) {
            return null;
        }
        if (column instanceof OffsetDateTime odt) {
            return odt;
        }
        if (column instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        if (column instanceof java.time.LocalDateTime ldt) {
            return ldt.atOffset(java.time.ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(String.valueOf(column));
    }

    public long insertReturningId(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Object> toList(Object jsonb) {
        if (jsonb == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(jsonb.toString(), List.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object jsonb) {
        if (jsonb == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonb.toString(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
