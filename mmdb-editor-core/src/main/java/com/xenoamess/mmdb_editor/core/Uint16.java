package com.xenoamess.mmdb_editor.core;

import java.util.Objects;

/**
 * uint16 显式包装（metadata 及需要精确 uint16 类型的值）。0..65535。
 */
public final class Uint16 {
    private final int value;

    public Uint16(int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("uint16 越界: " + value);
        }
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Uint16 u && u.value == value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "Uint16(" + value + ")";
    }
}
