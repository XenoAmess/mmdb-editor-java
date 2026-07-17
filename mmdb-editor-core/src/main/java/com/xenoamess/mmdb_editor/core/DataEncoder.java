package com.xenoamess.mmdb_editor.core;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MaxMind DB 数据区字段编码器（MaxMind-DB-spec v2.0 "Output Data Section"）。
 * 编码值模型：String→utf8, byte[]→bytes, Double→double, Float→float,
 * Integer→int32, Long→uint64, BigInteger→uint128, Boolean→boolean,
 * Uint16→uint16, Uint32→uint32, Map&lt;String,Object&gt;→map, List&lt;Object&gt;→array。
 */
public final class DataEncoder {

    public static final int TYPE_POINTER = 1;
    public static final int TYPE_UTF8 = 2;
    public static final int TYPE_DOUBLE = 3;
    public static final int TYPE_BYTES = 4;
    public static final int TYPE_UINT16 = 5;
    public static final int TYPE_UINT32 = 6;
    public static final int TYPE_MAP = 7;
    public static final int TYPE_INT32 = 8;
    public static final int TYPE_UINT64 = 9;
    public static final int TYPE_UINT128 = 10;
    public static final int TYPE_ARRAY = 11;
    public static final int TYPE_BOOLEAN = 14;
    public static final int TYPE_FLOAT = 15;

    private static final long POINTER_SIZE1_LIMIT = 2048;
    private static final long POINTER_SIZE2_LIMIT = 526336;
    private static final long POINTER_SIZE3_LIMIT = 526336 + (1L << 27);

    private DataEncoder() {
    }

    /**
     * 写入控制字节（含扩展类型与 29/30/31 三档扩展尺寸）。
     *
     * @param type 类型号（1-15）
     * @param size 负载尺寸（字节数；map/array 为元素数；≤ 16,843,036）
     */
    public static void writeControl(ByteArrayOutputStream out, int type, int size) {
        if (type < 1 || type > 15) {
            throw new IllegalArgumentException("type out of range: " + type);
        }
        if (size < 0 || size > 16843036) {
            throw new IllegalArgumentException("size out of range: " + size);
        }
        if (type < 8) {
            writeControlByte(out, type << 5, size);
        } else {
            writeControlByte(out, 0, size);
            out.write(type - 7);
        }
    }

    private static void writeControlByte(ByteArrayOutputStream out, int typeBits, int size) {
        if (size < 29) {
            out.write(typeBits | size);
        } else if (size < 285) {
            out.write(typeBits | 29);
            out.write(size - 29);
        } else if (size < 65821) {
            out.write(typeBits | 30);
            writeBe(out, size - 285, 2);
        } else {
            out.write(typeBits | 31);
            writeBe(out, size - 65821, 3);
        }
    }

    /**
     * 写入 pointer 字段（001SSVVV 四档）。value 为相对数据区起点的偏移。
     */
    public static void writePointer(ByteArrayOutputStream out, long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("pointer value out of range: " + value);
        }
        if (value < POINTER_SIZE1_LIMIT) {
            out.write((TYPE_POINTER << 5) | (int) ((value >> 8) & 0x7));
            out.write((int) (value & 0xFF));
        } else if (value < POINTER_SIZE2_LIMIT) {
            long v = value - POINTER_SIZE1_LIMIT;
            out.write((TYPE_POINTER << 5) | (1 << 3) | (int) ((v >> 16) & 0x7));
            writeBe(out, v & 0xFFFF, 2);
        } else if (value < POINTER_SIZE3_LIMIT) {
            long v = value - POINTER_SIZE2_LIMIT;
            out.write((TYPE_POINTER << 5) | (2 << 3) | (int) ((v >> 24) & 0x7));
            writeBe(out, v & 0xFFFFFF, 3);
        } else {
            out.write((TYPE_POINTER << 5) | (3 << 3));
            writeBe(out, value, 4);
        }
    }

    /**
     * 编码一个完整字段（控制字节 + 负载）。
     */
    @SuppressWarnings("unchecked")
    public static void encode(ByteArrayOutputStream out, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("mmdb 数据区不支持 null 值");
        } else if (value instanceof String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            writeControl(out, TYPE_UTF8, utf8.length);
            out.write(utf8, 0, utf8.length);
        } else if (value instanceof byte[] b) {
            writeControl(out, TYPE_BYTES, b.length);
            out.write(b, 0, b.length);
        } else if (value instanceof Double d) {
            writeControl(out, TYPE_DOUBLE, 8);
            writeBe(out, Double.doubleToLongBits(d), 8);
        } else if (value instanceof Float f) {
            writeControl(out, TYPE_FLOAT, 4);
            writeBe(out, Float.floatToIntBits(f) & 0xFFFFFFFFL, 4);
        } else if (value instanceof Boolean b) {
            writeControl(out, TYPE_BOOLEAN, b ? 1 : 0);
        } else if (value instanceof Integer v) {
            if (v >= 0) {
                byte[] b = minimalBe(v & 0xFFFFFFFFL);
                writeControl(out, TYPE_INT32, b.length);
                out.write(b, 0, b.length);
            } else {
                // 规范：短于最大长度的 int32 恒为正；负数必须写满 4 字节
                writeControl(out, TYPE_INT32, 4);
                writeBe(out, v & 0xFFFFFFFFL, 4);
            }
        } else if (value instanceof Long v) {
            if (v < 0) {
                throw new IllegalArgumentException("uint64 不接受负数: " + v);
            }
            byte[] b = minimalBe(v);
            writeControl(out, TYPE_UINT64, b.length);
            out.write(b, 0, b.length);
        } else if (value instanceof BigInteger v) {
            if (v.signum() < 0 || v.bitLength() > 128) {
                throw new IllegalArgumentException("uint128 越界: " + v);
            }
            byte[] b = minimalBe(v);
            writeControl(out, TYPE_UINT128, b.length);
            out.write(b, 0, b.length);
        } else if (value instanceof Uint16 u) {
            byte[] b = minimalBe(u.getValue() & 0xFFFFL);
            writeControl(out, TYPE_UINT16, b.length);
            out.write(b, 0, b.length);
        } else if (value instanceof Uint32 u) {
            byte[] b = minimalBe(u.getValue());
            writeControl(out, TYPE_UINT32, b.length);
            out.write(b, 0, b.length);
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            writeControl(out, TYPE_MAP, map.size());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                encode(out, e.getKey());
                encode(out, e.getValue());
            }
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            writeControl(out, TYPE_ARRAY, list.size());
            for (Object e : list) {
                encode(out, e);
            }
        } else {
            throw new IllegalArgumentException("不支持的值类型: " + value.getClass().getName());
        }
    }

    /** 最小字节数大端编码（0 值编码为 0 字节） */
    static byte[] minimalBe(long value) {
        if (value == 0) {
            return new byte[0];
        }
        int bytes = 0;
        long t = value;
        while (t != 0) {
            bytes++;
            t >>>= 8;
        }
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[bytes - 1 - i] = (byte) (value >>> (i * 8));
        }
        return out;
    }

    private static byte[] minimalBe(BigInteger value) {
        if (value.signum() == 0) {
            return new byte[0];
        }
        byte[] raw = value.toByteArray();
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) {
            start++;
        }
        byte[] out = new byte[raw.length - start];
        System.arraycopy(raw, start, out, 0, out.length);
        return out;
    }

    /** 大端无符号写入（低 bytes 字节） */
    public static void writeBe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }
}
