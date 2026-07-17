package com.xenoamess.mmdb_editor.core;

import java.io.ByteArrayOutputStream;

/**
 * 树节点 record 打包（24/28/32 位三档，大端）。
 * 28 位布局：左低 24 位（3 字节）| 中间字节（左高 4 位 &lt;&lt;4 | 右高 4 位）| 右低 24 位（3 字节）。
 */
final class TreeSerializer {

    private TreeSerializer() {
    }

    static void writeNode(ByteArrayOutputStream out, long left, long right, int recordSize) {
        switch (recordSize) {
            case 24 -> {
                checkRange(left, 24);
                checkRange(right, 24);
                DataEncoder.writeBe(out, left, 3);
                DataEncoder.writeBe(out, right, 3);
            }
            case 28 -> {
                checkRange(left, 28);
                checkRange(right, 28);
                DataEncoder.writeBe(out, left & 0xFFFFFF, 3);
                out.write((int) (((left >> 24) & 0xF) << 4 | ((right >> 24) & 0xF)));
                DataEncoder.writeBe(out, right & 0xFFFFFF, 3);
            }
            case 32 -> {
                checkRange(left, 32);
                checkRange(right, 32);
                DataEncoder.writeBe(out, left, 4);
                DataEncoder.writeBe(out, right, 4);
            }
            default -> throw new IllegalArgumentException("recordSize 仅支持 24/28/32: " + recordSize);
        }
    }

    private static void checkRange(long value, int bits) {
        if (value < 0 || value >= (1L << bits)) {
            throw new IllegalArgumentException("record 值超出 " + bits + " 位: " + value);
        }
    }

    /** 给定 recordSize 下每节点字节数 */
    static int nodeBytes(int recordSize) {
        return recordSize * 2 / 8;
    }
}
