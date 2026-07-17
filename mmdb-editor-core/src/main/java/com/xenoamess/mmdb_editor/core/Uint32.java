package com.xenoamess.mmdb_editor.core;

import java.util.Objects;

/**
 * uint32 显式包装（metadata 及需要精确 uint32 类型的值）。0..4294967295。
 */
public final class Uint32 {
    private final long value;

    public Uint32(long value) {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("uint32 越界: " + value);
        }
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Uint32 u && u.value == value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "Uint32(" + value + ")";
    }
}
