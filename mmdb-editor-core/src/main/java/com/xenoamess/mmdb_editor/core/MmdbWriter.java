package com.xenoamess.mmdb_editor.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MaxMind DB writer（MaxMind-DB-spec v2.0）。Builder 用法：
 * <pre>{@code
 * MmdbWriter.writer(MmdbWriter.IpVersion.V6_TREE_128)
 *         .databaseType("my-db")
 *         .insert(addr, 32, Map.of("country", "中国"))
 *         .aliasIpv4InV6()
 *         .write(out);
 * }</pre>
 */
public final class MmdbWriter {

    /** 树位长：v4-only 32 位树，或 v6/混合 128 位树 */
    public enum IpVersion {
        V4_TREE_32(32, 4),
        V6_TREE_128(128, 6);

        final int bits;
        final int metadataVersion;

        IpVersion(int bits, int metadataVersion) {
            this.bits = bits;
            this.metadataVersion = metadataVersion;
        }
    }

    private static final byte[] METADATA_MARKER = new byte[]{
            (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            'M', 'a', 'x', 'M', 'i', 'n', 'd', '.', 'c', 'o', 'm'};

    private MmdbWriter() {
    }

    public static Builder writer(IpVersion ipVersion) {
        return new Builder(ipVersion);
    }

    public static final class Builder {
        private final Trie trie;
        private final IpVersion ipVersion;
        private int recordSize;
        private String databaseType = "mmdb-editor";
        private Long buildEpoch;
        private List<String> languages;
        private final Map<String, String> description = new LinkedHashMap<>();

        private Builder(IpVersion ipVersion) {
            this.ipVersion = ipVersion;
            this.trie = new Trie(ipVersion.bits);
        }

        /** record 位宽：24/28/32；0（默认）= 自动取最小可用档 */
        public Builder recordSize(int recordSize) {
            if (recordSize != 0 && recordSize != 24 && recordSize != 28 && recordSize != 32) {
                throw new IllegalArgumentException("recordSize 仅支持 0/24/28/32: " + recordSize);
            }
            this.recordSize = recordSize;
            return this;
        }

        public Builder databaseType(String databaseType) {
            if (databaseType != null && databaseType.startsWith("GeoIP")) {
                throw new IllegalArgumentException("database_type 不得以 GeoIP 开头（MaxMind 保留）");
            }
            this.databaseType = databaseType;
            return this;
        }

        public Builder buildEpoch(long epochSeconds) {
            this.buildEpoch = epochSeconds;
            return this;
        }

        public Builder languages(List<String> languages) {
            this.languages = languages;
            return this;
        }

        public Builder description(String language, String text) {
            this.description.put(language, text);
            return this;
        }

        /**
         * 插入一条前缀记录。record 取值见 {@link DataEncoder} 值模型。
         * 更深前缀穿过已有记录时自动分裂叶子；精确深度处覆盖占位叶子。
         */
        public Builder insert(byte[] address, int prefixLength, Object record) {
            trie.insert(address, prefixLength, record);
            return this;
        }

        /**
         * 在指定前缀处打"未命中"空洞。
         */
        public Builder insertEmpty(byte[] address, int prefixLength) {
            trie.insertEmpty(address, prefixLength);
            return this;
        }

        /**
         * MaxMind 惯例的 v4 别名：`::ffff:0/96` → `::/96` 的 v4 子树根。
         * 仅 V6_TREE_128 可用；无 v4 数据时为空操作。
         */
        public Builder aliasIpv4InV6() {
            if (ipVersion != IpVersion.V6_TREE_128) {
                throw new IllegalStateException("aliasIpv4InV6 仅适用于 128 位树");
            }
            int v4root = trie.findNode(new byte[12], 96);
            if (v4root >= 0) {
                byte[] ffff = new byte[12];
                ffff[10] = (byte) 0xFF;
                ffff[11] = (byte) 0xFF;
                trie.alias(ffff, 96, v4root);
            }
            return this;
        }

        /** 序列化并写出整个 mmdb 文件 */
        public void write(Path out) throws IOException {
            Files.write(out, build());
        }

        /** 序列化为字节数组 */
        public byte[] build() {
            trie.compress();
            List<Trie.Node> nodes = trie.renumber();
            long nodeCount = nodes.size();

            // 1. 先编码数据区，建立 record → dataOffset 映射
            DataSectionWriter data = new DataSectionWriter();
            Map<Object, Long> recordOffsets = new LinkedHashMap<>();
            for (Trie.Node n : nodes) {
                collectRecords(n.left, data, recordOffsets);
                collectRecords(n.right, data, recordOffsets);
            }

            // 2. record_size 选择
            int rs = recordSize != 0 ? recordSize : pickRecordSize(nodeCount + 16 + data.size());

            // 3. 序列化树
            ByteArrayOutputStream treeOut = new ByteArrayOutputStream();
            for (Trie.Node n : nodes) {
                long left = childValue(n.left, nodeCount, recordOffsets);
                long right = childValue(n.right, nodeCount, recordOffsets);
                TreeSerializer.writeNode(treeOut, left, right, rs);
            }

            // 4. 组装：树 + 16 字节分隔 + 数据区 + 尾标 + metadata
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.writeBytes(treeOut.toByteArray());
            file.writeBytes(new byte[16]);
            file.writeBytes(data.toByteArray());
            file.writeBytes(METADATA_MARKER);
            file.writeBytes(buildMetadata(nodeCount, rs));
            return file.toByteArray();
        }

        private static void collectRecords(Object slot, DataSectionWriter data, Map<Object, Long> recordOffsets) {
            if (slot != null && !(slot instanceof Integer) && slot != Trie.EMPTY && !recordOffsets.containsKey(slot)) {
                // writeValue 返回字段真实起点（内容去重时指回首次偏移），避免树指针指向 pointer
                recordOffsets.put(slot, data.writeValue(slot));
            }
        }

        private static long childValue(Object slot, long nodeCount, Map<Object, Long> recordOffsets) {
            if (slot == null || slot == Trie.EMPTY) {
                return nodeCount;
            }
            if (slot instanceof Integer idx) {
                return idx;
            }
            return nodeCount + 16 + recordOffsets.get(slot);
        }

        private static int pickRecordSize(long maxValue) {
            if (maxValue < (1L << 24)) {
                return 24;
            }
            if (maxValue < (1L << 28)) {
                return 28;
            }
            if (maxValue < (1L << 32)) {
                return 32;
            }
            throw new IllegalStateException("数据区超出 32 位 record 可寻址范围");
        }

        private byte[] buildMetadata(long nodeCount, int rs) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("binary_format_major_version", new Uint16(2));
            meta.put("binary_format_minor_version", new Uint16(0));
            meta.put("build_epoch", buildEpoch != null ? buildEpoch : System.currentTimeMillis() / 1000);
            meta.put("database_type", databaseType);
            if (languages != null && !languages.isEmpty()) {
                meta.put("languages", new ArrayList<>(languages));
            }
            if (!description.isEmpty()) {
                meta.put("description", new LinkedHashMap<>(description));
            }
            meta.put("ip_version", new Uint16(ipVersion.metadataVersion));
            meta.put("node_count", new Uint32(nodeCount));
            meta.put("record_size", new Uint16(rs));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataEncoder.encode(out, meta);
            return out.toByteArray();
        }
    }
}
