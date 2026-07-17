package com.xenoamess.mmdb_editor.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DataEncoder 金样测试，向量取自 MaxMind-DB-spec v2.0 内嵌示例。
 */
class DataEncoderGoldenTest {

    private static byte[] bytes(int... vals) {
        byte[] out = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            out[i] = (byte) vals[i];
        }
        return out;
    }

    @Test
    void controlByteSpecExamples() {
        // spec: 01011101 00110011 UTF-8 string - 80 bytes long
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataEncoder.writeControl(out, DataEncoder.TYPE_UTF8, 80);
        assertArrayEquals(bytes(0b01011101, 0b00110011), out.toByteArray());

        // spec: 01011110 00110011 00110011 UTF-8 string - 13,392 bytes long
        out = new ByteArrayOutputStream();
        DataEncoder.writeControl(out, DataEncoder.TYPE_UTF8, 13392);
        assertArrayEquals(bytes(0b01011110, 0b00110011, 0b00110011), out.toByteArray());

        // spec: 01011111 00110011×3 UTF-8 string - 3,421,264 bytes long
        out = new ByteArrayOutputStream();
        DataEncoder.writeControl(out, DataEncoder.TYPE_UTF8, 3421264);
        assertArrayEquals(bytes(0b01011111, 0b00110011, 0b00110011, 0b00110011), out.toByteArray());

        // spec: 00000011 00000011 unsigned 128-bit int - 3 bytes long
        out = new ByteArrayOutputStream();
        DataEncoder.writeControl(out, DataEncoder.TYPE_UINT128, 3);
        assertArrayEquals(bytes(0b00000011, 0b00000011), out.toByteArray());

        // spec: 000XXXXX 00000100 array（扩展类型 11-7=4）
        out = new ByteArrayOutputStream();
        DataEncoder.writeControl(out, DataEncoder.TYPE_ARRAY, 0);
        assertArrayEquals(bytes(0b00000000, 0b00000100), out.toByteArray());
    }

    @Test
    void pointerEncodingFourClasses() {
        // 档 0：11 位，值 < 2048
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataEncoder.writePointer(out, 0x03FF);
        assertArrayEquals(bytes(0b00100011, 0xFF), out.toByteArray());

        // 档 1：19 位 + 2048
        out = new ByteArrayOutputStream();
        DataEncoder.writePointer(out, 2048 + 0x12345);
        assertArrayEquals(bytes(0b00101001, 0x23, 0x45), out.toByteArray());

        // 档 2：27 位 + 526336
        out = new ByteArrayOutputStream();
        DataEncoder.writePointer(out, 526336 + 0x1234567);
        assertArrayEquals(bytes(0b00110001, 0x23, 0x45, 0x67), out.toByteArray());

        // 档 3：32 位直读
        out = new ByteArrayOutputStream();
        DataEncoder.writePointer(out, 0xF1234567L);
        assertArrayEquals(bytes(0b00111000, 0xF1, 0x23, 0x45, 0x67), out.toByteArray());
    }

    @Test
    void numericEncodings() {
        // uint64 最小字节；0 → 0 字节
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataEncoder.encode(out, 0L);
        assertArrayEquals(bytes(0b00000000, 2), out.toByteArray()); // 扩展 uint64(9-7=2), size 0

        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, 0x0102L);
        assertArrayEquals(bytes(0b00000010, 2, 0x01, 0x02), out.toByteArray());

        // int32 正数最小字节；负数写满 4 字节
        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, 0x7F);
        assertArrayEquals(bytes(0b00000001, 1, 0x7F), out.toByteArray());

        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, -1);
        assertArrayEquals(bytes(0b00000100, 1, 0xFF, 0xFF, 0xFF, 0xFF), out.toByteArray());

        // uint128
        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, new BigInteger("0102", 16));
        assertArrayEquals(bytes(0b00000010, 3, 0x01, 0x02), out.toByteArray());

        // boolean：size 即值，无负载
        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, true);
        assertArrayEquals(bytes(0b00000001, 7), out.toByteArray()); // 扩展 boolean(14-7=7), size 1

        // uint 负数拒绝
        assertThrows(IllegalArgumentException.class, () -> DataEncoder.encode(new ByteArrayOutputStream(), -1L));
    }

    @Test
    void compositeEncodings() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataEncoder.encode(out, Map.of("a", "b"));
        // map(7)<<5|1 = 0xE1, "a": 2<<5|1=0x41 'a', "b": 0x41 'b'
        assertArrayEquals(bytes(0xE1, 0x41, 'a', 0x41, 'b'), out.toByteArray());

        out = new ByteArrayOutputStream();
        DataEncoder.encode(out, List.of(1.5d, "x"));
        // array 扩展: 0x02(size=2 低5位)|0... type=0 size=2 → 0x02, ext 4
        assertArrayEquals(bytes(0x02, 4, 0x68, 0x3F, 0xF8, 0, 0, 0, 0, 0, 0, 0x41, 'x'), out.toByteArray());
    }

    @Test
    void treeSerializerThreeSizes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TreeSerializer.writeNode(out, 0x010203, 0x040506, 24);
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), out.toByteArray());

        // 28 位：左 0x1234567，右 0x89ABCDE
        out = new ByteArrayOutputStream();
        TreeSerializer.writeNode(out, 0x1234567, 0x89ABCDE, 28);
        assertArrayEquals(bytes(0x23, 0x45, 0x67, 0x18, 0x9A, 0xBC, 0xDE), out.toByteArray());

        out = new ByteArrayOutputStream();
        TreeSerializer.writeNode(out, 0x01020304, 0xA0B0C0D0L, 32);
        assertArrayEquals(bytes(1, 2, 3, 4, 0xA0, 0xB0, 0xC0, 0xD0), out.toByteArray());

        assertThrows(IllegalArgumentException.class,
                () -> TreeSerializer.writeNode(new ByteArrayOutputStream(), 1L << 24, 0, 24));
    }
}
