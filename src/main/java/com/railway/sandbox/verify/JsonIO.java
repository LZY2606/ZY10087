package com.railway.sandbox.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Single ObjectMapper used everywhere: order-sensitive map entries are
 * inserted pre-sorted, and no pretty printing is used for fingerprints, so
 * the same logical object hashes identically on every machine.
 */
public final class JsonIO {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

    private JsonIO() {}

    public static ObjectMapper mapper() { return MAPPER; }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败", e);
        }
    }
}
