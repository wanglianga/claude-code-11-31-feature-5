package com.mdm.platform.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.postgresql.util.PGobject;

import java.io.IOException;

/**
 * PostgreSQL json/jsonb 列经 JdbcTemplate 返回 PGobject，
 * 直接作为原始 JSON 内嵌输出，避免出现 {"type":"jsonb","value":"..."} 包装。
 */
public class PgObjectSerializer extends JsonSerializer<PGobject> {

    @Override
    public void serialize(PGobject value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || value.getValue() == null) {
            gen.writeNull();
            return;
        }
        String type = value.getType();
        String raw = value.getValue();
        if ("jsonb".equalsIgnoreCase(type) || "json".equalsIgnoreCase(type)) {
            gen.writeRawValue(raw);
        } else {
            gen.writeString(raw);
        }
    }
}
