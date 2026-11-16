package com.mdm.platform.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * PostgreSQL 数组列经 JdbcTemplate 返回 java.sql.Array，
 * 默认 Jackson 会尝试序列化驱动内部类而失败，这里统一转成 JSON 数组。
 */
public class SqlArraySerializer extends JsonSerializer<Array> {

    @Override
    public void serialize(Array value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        try {
            Object array = value.getArray();
            if (array instanceof Object[] objects) {
                List<Object> list = Arrays.asList(objects);
                gen.writeObject(list);
            } else {
                gen.writeObject(array);
            }
        } catch (SQLException e) {
            throw new IOException("序列化 PG 数组失败", e);
        }
    }
}
