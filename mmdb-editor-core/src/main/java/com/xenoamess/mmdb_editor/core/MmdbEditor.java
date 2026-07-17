package com.xenoamess.mmdb_editor.core;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mmdb 编辑器：流式 transform——遍历源文件，逐条应用变换函数，写出新文件。
 * 源记录不整体进堆（单条粒度）。metadata（database_type/languages/description）随源保留。
 */
public final class MmdbEditor {

    /**
     * 记录变换函数。
     *
     * @return 新记录；返回 null 表示删除该前缀（不写出新文件中）
     */
    @FunctionalInterface
    public interface RecordTransform {
        Object apply(byte[] address, int prefixLength, Object record);
    }

    private MmdbEditor() {
    }

    /**
     * 流式改写 mmdb 文件。
     *
     * @param src 源文件
     * @param dst 目标文件（可与源相同，调用方需自行先写临时文件再原子替换）
     * @param transform 变换函数
     */
    public static void transform(Path src, Path dst, RecordTransform transform) throws IOException {
        MmdbReader reader = MmdbReader.open(src);
        MmdbWriter.Builder builder = toBuilder(reader.metadata());
        reader.walk((address, prefixLength, record) -> {
            Object replaced = transform.apply(address, prefixLength, record);
            if (replaced != null) {
                builder.insert(address, prefixLength, replaced);
            }
        });
        if (reader.ipVersion() == 6) {
            builder.aliasIpv4InV6();
        }
        builder.write(dst);
    }

    /** metadata map → writer builder（保留 database_type/languages/description/build_epoch） */
    @SuppressWarnings("unchecked")
    private static MmdbWriter.Builder toBuilder(Map<String, Object> metadata) {
        int ipVersion = ((Number) metadata.get("ip_version")).intValue();
        MmdbWriter.Builder builder = MmdbWriter.writer(
                ipVersion == 4 ? MmdbWriter.IpVersion.V4_TREE_32 : MmdbWriter.IpVersion.V6_TREE_128);
        Object dbType = metadata.get("database_type");
        if (dbType instanceof String s && !s.isEmpty()) {
            builder.databaseType(s);
        }
        Object langs = metadata.get("languages");
        if (langs instanceof List) {
            builder.languages(new ArrayList<>((List<String>) langs));
        }
        Object desc = metadata.get("description");
        if (desc instanceof Map) {
            ((Map<String, String>) desc).forEach(builder::description);
        }
        Object epoch = metadata.get("build_epoch");
        if (epoch instanceof Number n) {
            builder.buildEpoch(n.longValue());
        } else if (epoch instanceof BigInteger bi) {
            builder.buildEpoch(bi.longValue());
        }
        return builder;
    }

    /** 便捷：单条插入/覆盖 */
    public static void upsert(Path src, Path dst, byte[] address, int prefixLength, Object record) throws IOException {
        transform(src, dst, (addr, len, old) -> {
            if (len == prefixLength && java.util.Arrays.equals(addr, address)) {
                return record;
            }
            return old;
        });
    }

    /** 便捷：删除指定前缀 */
    public static void delete(Path src, Path dst, byte[] address, int prefixLength) throws IOException {
        transform(src, dst, (addr, len, old) ->
                len == prefixLength && java.util.Arrays.equals(addr, address) ? null : old);
    }
}
