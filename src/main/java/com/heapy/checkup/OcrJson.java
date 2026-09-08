package com.heapy.checkup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class OcrJson {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private OcrJson() { }

    public static String encode(Object value) {
        return MAPPER.writeValueAsString(sorted(MAPPER.valueToTree(value)));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> values = new TreeMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), sorted(entry.getValue())));
            return MAPPER.valueToTree(values);
        }
        if (node.isArray()) {
            var array = MAPPER.createArrayNode();
            node.forEach(value -> array.add(sorted(value)));
            return array;
        }
        return node;
    }

    public static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
        }
    }
}
