package com.xenoamess.mmdb_editor.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mmdb 数据区字段解码器。输出与 {@link DataEncoder} 值模型一致；
 * pointer 解析为所指字段的值。
 */
final class MmdbDecoder {

    private final byte[] data;
    private final int base; // 数据区在文件中的起点（pointer 偏移以此为 0）
    private int pos;

    MmdbDecoder(byte[] data, int base) {
        this.data = data;
        this.base = base;
    }

    void position(int dataRelativeOffset) {
        this.pos = dataRelativeOffset;
    }

    int position() {
        return pos;
    }

    Object decode() {
        int ctrl = data[base + pos++] & 0xFF;
        int type = ctrl >> 5;
        if (type == 0) {
            type = (data[base + pos++] & 0xFF) + 7;
        }
        int size = readSize(ctrl);

        switch (type) {
            case DataEncoder.TYPE_POINTER -> {
                long target = readPointerValue(ctrl);
                int saved = pos;
                pos = (int) target;
                Object value = decode();
                pos = saved;
                return value;
            }
            case DataEncoder.TYPE_UTF8 -> {
                String s = new String(data, base + pos, size, StandardCharsets.UTF_8);
                pos += size;
                return s;
            }
            case DataEncoder.TYPE_DOUBLE -> {
                requireSize(type, size, 8);
                long bits = readBe(8);
                return Double.longBitsToDouble(bits);
            }
            case DataEncoder.TYPE_BYTES -> {
                byte[] b = new byte[size];
                System.arraycopy(data, base + pos, b, 0, size);
                pos += size;
                return b;
            }
            case DataEncoder.TYPE_UINT16, DataEncoder.TYPE_UINT32, DataEncoder.TYPE_UINT64 -> {
                return readBe(size);
            }
            case DataEncoder.TYPE_UINT128 -> {
                if (size == 0) {
                    return BigInteger.ZERO;
                }
                byte[] b = new byte[size];
                System.arraycopy(data, base + pos, b, 0, size);
                pos += size;
                return new BigInteger(1, b);
            }
            case DataEncoder.TYPE_INT32 -> {
                if (size == 4) {
                    return (int) readBe(4);
                }
                // 规范：短于最大长度恒为正
                return (int) readBe(size);
            }
            case DataEncoder.TYPE_MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = (String) decode();
                    map.put(key, decode());
                }
                return map;
            }
            case DataEncoder.TYPE_ARRAY -> {
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(decode());
                }
                return list;
            }
            case DataEncoder.TYPE_BOOLEAN -> {
                return size != 0;
            }
            case DataEncoder.TYPE_FLOAT -> {
                requireSize(type, size, 4);
                int bits = (int) readBe(4);
                return Float.intBitsToFloat(bits);
            }
            default -> throw new IllegalArgumentException("未知类型: " + type);
        }
    }

    private static void requireSize(int type, int size, int expected) {
        if (size != expected) {
            throw new IllegalArgumentException("类型 " + type + " 的尺寸应为 " + expected + "，实际 " + size);
        }
    }

    private int readSize(int ctrl) {
        int size = ctrl & 0x1F;
        if (size < 29) {
            return size;
        }
        if (size == 29) {
            return 29 + (data[base + pos++] & 0xFF);
        }
        if (size == 30) {
            int v = (int) readBe(2);
            return 285 + v;
        }
        int v = (int) readBe(3);
        return 65821 + v;
    }

    private long readPointerValue(int ctrl) {
        int sizeBits = ctrl & 0x1F;
        int ss = (sizeBits >> 3) & 0x3;
        int vvv = sizeBits & 0x7;
        return switch (ss) {
            case 0 -> ((long) vvv << 8) | (data[base + pos++] & 0xFF);
            case 1 -> (((long) vvv << 16) | readBe(2)) + 2048;
            case 2 -> (((long) vvv << 24) | readBe(3)) + 526336;
            default -> readBe(4);
        };
    }

    /** 读 bytes 字节大端无符号并前进 */
    private long readBe(int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 8) | (data[base + pos++] & 0xFF);
        }
        return v;
    }
}
