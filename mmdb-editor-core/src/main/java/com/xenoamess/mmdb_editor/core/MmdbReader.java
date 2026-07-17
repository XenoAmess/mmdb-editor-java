package com.xenoamess.mmdb_editor.core;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * mmdb 读取器：metadata 解析、点查（lookup）、全量遍历（walk）。
 * 输出值模型与 {@link DataEncoder} 一致。
 */
public final class MmdbReader {

    /** 遍历回调 */
    public interface WalkCallback {
        void onNetwork(byte[] address, int prefixLength, Object record);
    }

    private static final byte[] METADATA_MARKER = new byte[]{
            (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'};

    private final byte[] file;
    private final long nodeCount;
    private final int recordSize;
    private final int ipVersion;
    private final int treeSize;
    private final Map<String, Object> metadata;
    private final MmdbDecoder decoder;
    private final int ipv4StartNode;

    private MmdbReader(byte[] file, Map<String, Object> metadata) {
        this.file = file;
        this.metadata = metadata;
        this.nodeCount = asLong(metadata.get("node_count"));
        this.recordSize = (int) asLong(metadata.get("record_size"));
        this.ipVersion = (int) asLong(metadata.get("ip_version"));
        this.treeSize = (int) (nodeCount * (recordSize * 2L / 8));
        this.decoder = new MmdbDecoder(file, treeSize + 16);
        this.ipv4StartNode = ipVersion == 6 ? prewalkIpv4Start() : 0;
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof BigInteger bi) {
            return bi.longValue();
        }
        throw new IllegalArgumentException("metadata 数值字段缺失或类型错误: " + v);
    }

    /** 打开并解析 mmdb 文件 */
    @SuppressWarnings("unchecked")
    public static MmdbReader open(Path path) throws IOException {
        byte[] file = Files.readAllBytes(path);
        int marker = lastIndexOf(file, METADATA_MARKER);
        if (marker < 0) {
            throw new IOException("不是有效的 mmdb 文件（缺少 metadata 尾标）");
        }
        MmdbDecoder metaDecoder = new MmdbDecoder(file, 0);
        metaDecoder.position(marker + METADATA_MARKER.length);
        Object meta = metaDecoder.decode();
        if (!(meta instanceof Map)) {
            throw new IOException("mmdb metadata 不是 map");
        }
        return new MmdbReader(file, (Map<String, Object>) meta);
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 原始 metadata map */
    public Map<String, Object> metadata() {
        return metadata;
    }

    public long nodeCount() {
        return nodeCount;
    }

    public int recordSize() {
        return recordSize;
    }

    public int ipVersion() {
        return ipVersion;
    }

    /** 查询，未命中返回 null */
    public Object lookup(InetAddress address) {
        return lookup(address.getAddress());
    }

    /** 查询，未命中返回 null。address 为 4（v4）或 16（v6）字节 */
    public Object lookup(byte[] address) {
        long node = 0;
        int startBit = 0;
        if (ipVersion == 6 && address.length == 4) {
            node = ipv4StartNode;
            // ipv4Start 已是 96 位预走后的节点
            byte[] mapped = new byte[16];
            System.arraycopy(address, 0, mapped, 12, 4);
            address = mapped;
            startBit = 96;
        }
        int bitLength = address.length * 8;
        for (int pl = startBit; pl < bitLength && node < nodeCount; pl++) {
            int bit = (address[pl / 8] >> (7 - pl % 8)) & 1;
            node = readChild(node, bit);
        }
        if (node <= nodeCount) {
            return null;
        }
        decoder.position((int) (node - nodeCount - 16));
        return decoder.decode();
    }

    private int prewalkIpv4Start() {
        long node = 0;
        for (int i = 0; i < 96 && node < nodeCount; i++) {
            node = readChild(node, 0);
        }
        return (int) node;
    }

    /** 读节点 node 的 bit 侧 record 值 */
    private long readChild(long node, int bit) {
        long offset = node * (recordSize * 2L / 8);
        return switch (recordSize) {
            case 24 -> {
                offset += (long) bit * 3;
                yield be(offset, 3);
            }
            case 28 -> {
                offset += (long) bit * 3;
                long low24 = be(offset, 3);
                int middle = file[(int) (node * 7 + 3)] & 0xFF;
                int high4 = bit == 0 ? (middle >> 4) & 0xF : middle & 0xF;
                yield ((long) high4 << 24) | low24;
            }
            case 32 -> {
                offset += (long) bit * 4;
                yield be(offset, 4);
            }
            default -> throw new IllegalStateException("recordSize 非法: " + recordSize);
        };
    }

    private long be(long offset, int bytes) {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 8) | (file[(int) offset + i] & 0xFF);
        }
        return v;
    }

    /**
     * 深度优先遍历。ip_version=6 时按 MaxMind 惯例剪除 ::ffff:0/96 别名子树
     * （该子树是 ::/96 的引用，不是独立数据）。
     */
    public void walk(WalkCallback callback) {
        int bitLength = ipVersion == 4 ? 32 : 128;
        walkNode(0, new byte[bitLength / 8], 0, callback);
    }

    private void walkNode(long nodeIndex, byte[] address, int depth, WalkCallback callback) {
        long left = readChild(nodeIndex, 0);
        long right = readChild(nodeIndex, 1);
        if (left == right && left > nodeCount) {
            emit(left, address, depth, callback);
            return;
        }
        walkChild(left, address, depth, 0, callback);
        walkChild(right, address, depth, 1, callback);
    }

    private void walkChild(long child, byte[] address, int depth, int bit, WalkCallback callback) {
        if (child == nodeCount) {
            return;
        }
        if (bit == 1) {
            address[depth / 8] |= (byte) (0x80 >> (depth % 8));
        }
        // 剪除 ::ffff:0/96 别名子树
        if (ipVersion == 6 && depth + 1 == 96 && isIpv4AliasPrefix(address)) {
            address[depth / 8] &= (byte) ~(0x80 >> (depth % 8));
            return;
        }
        if (child > nodeCount) {
            emit(child, address, depth + 1, callback);
        } else {
            walkNode(child, address, depth + 1, callback);
        }
        address[depth / 8] &= (byte) ~(0x80 >> (depth % 8));
    }

    private static boolean isIpv4AliasPrefix(byte[] address) {
        // 80 位 0 + 16 位 1，即前 10 字节全 0，第 11-12 字节 0xFFFF
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    }

    private void emit(long child, byte[] address, int prefixLength, WalkCallback callback) {
        decoder.position((int) (child - nodeCount - 16));
        Object record = decoder.decode();
        callback.onNetwork(Arrays.copyOf(address, address.length), prefixLength, record);
    }
}
