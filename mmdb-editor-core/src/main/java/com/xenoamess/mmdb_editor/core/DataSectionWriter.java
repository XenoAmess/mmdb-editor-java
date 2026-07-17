package com.xenoamess.mmdb_editor.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据区写入器：按出现顺序编码值，重复值（深度相等）改写 pointer 指回首次偏移。
 * 嵌套 map/array 的子值同样经过去重通道。pointer 永不指向 pointer（被指的总是字段起点）。
 */
final class DataSectionWriter {

    /** byte[] 的去重键包装（数组内容相等） */
    private record ByteArrayKey(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ByteArrayKey k && Arrays.equals(bytes, k.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private final Map<Object, Long> offsets = new HashMap<>();

    byte[] toByteArray() {
        return buf.toByteArray();
    }

    int size() {
        return buf.size();
    }

    /**
     * 写入一个值并返回其字段起点偏移（去重命中时返回首次写入的偏移）。
     */
    long writeValue(Object value) {
        Object key = normalize(value);
        Long cached = offsets.get(key);
        if (cached != null) {
            DataEncoder.writePointer(buf, cached);
            return cached;
        }
        long offset = buf.size();
        offsets.put(key, offset);
        encodeWithDedup(value);
        return offset;
    }

    @SuppressWarnings("unchecked")
    private void encodeWithDedup(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            DataEncoder.writeControl(buf, DataEncoder.TYPE_MAP, map.size());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                writeValue(e.getKey());
                writeValue(e.getValue());
            }
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            DataEncoder.writeControl(buf, DataEncoder.TYPE_ARRAY, list.size());
            for (Object e : list) {
                writeValue(e);
            }
        } else {
            DataEncoder.encode(buf, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof byte[] b) {
            return new ByteArrayKey(b);
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                copy.put(e.getKey(), normalize(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object e : (List<Object>) value) {
                copy.add(normalize(e));
            }
            return copy;
        }
        return Objects.requireNonNull(value, "mmdb 数据区不支持 null 值");
    }
}
