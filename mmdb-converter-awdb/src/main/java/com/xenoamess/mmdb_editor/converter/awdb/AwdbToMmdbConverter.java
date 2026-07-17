package com.xenoamess.mmdb_editor.converter.awdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.xenoamess.ipplus360.AwdbReader;
import com.xenoamess.ipplus360.enumerate.FileOpenMode;
import com.xenoamess.ipplus360.impl.AwdbCacheImpl;
import com.xenoamess.mmdb_editor.core.MmdbWriter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * awdb (Gen-B) → mmdb 转换器。
 * 布局映射：
 * <ul>
 *   <li>ip_version "4" → mmdb ip_version=4，32 位树直搬</li>
 *   <li>"6" → ip_version=6，128 位树直搬</li>
 *   <li>"4_6" → ip_version=6；awdb 的 ::ffff:0/96 子树重 rooting 到 ::/96，
 *       并按 MaxMind 惯例在 ::ffff:0:0/96 写别名</li>
 * </ul>
 */
public final class AwdbToMmdbConverter {

    private AwdbToMmdbConverter() {
    }

    /**
     * 转换 awdb 文件为 mmdb 文件。
     *
     * @param awdbFile 源 awdb (Gen-B) 文件
     * @param mmdbOut  输出 mmdb 文件
     */
    public static void convert(Path awdbFile, Path mmdbOut) throws IOException {
        try (AwdbReader reader = AwdbReader.open(awdbFile.toFile(), new AwdbCacheImpl(), FileOpenMode.MEMORY)) {
            String ipVersion = reader.getAwdbMetaData().getIpVersion();
            boolean v4Tree = "4".equals(ipVersion);
            MmdbWriter.Builder builder = MmdbWriter.writer(
                    v4Tree ? MmdbWriter.IpVersion.V4_TREE_32 : MmdbWriter.IpVersion.V6_TREE_128);
            builder.databaseType("awdb-converted")
                    .description("en", "converted from awdb (" + reader.getAwdbMetaData().getFileName() + ")")
                    .buildEpoch(parseBuildEpoch(reader.getAwdbMetaData().getCreateTime()));

            reader.walk((address, prefixLength, record) -> {
                Object value = toMmdbValue(record);
                if (value == null) {
                    return;
                }
                if ("4_6".equals(ipVersion) && isIpv4Mapped(address)) {
                    // awdb: v4 挂在 ::ffff:0/96 下；mmdb: v4 内容放 ::/96
                    byte[] rerooted = new byte[16];
                    System.arraycopy(address, 12, rerooted, 12, 4);
                    builder.insert(rerooted, prefixLength, value);
                } else {
                    builder.insert(address, prefixLength, value);
                }
            });

            if ("4_6".equals(ipVersion)) {
                builder.aliasIpv4InV6();
            }
            builder.write(mmdbOut);
        }
    }

    private static long parseBuildEpoch(String createTime) {
        if (createTime != null) {
            try {
                return LocalDate.parse(createTime.trim()).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // 落到默认当前时间
            }
        }
        return System.currentTimeMillis() / 1000;
    }

    /** ::ffff:0/96 前缀判断：前 10 字节全 0，第 11-12 字节 0xFFFF */
    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    }

    /**
     * JsonNode → mmdb 值模型（见 DataEncoder）。
     * null/missing 值丢弃；map 中值为 null 的字段跳过。
     */
    public static Object toMmdbValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                Object v = toMmdbValue(e.getValue());
                if (v != null) {
                    map.put(e.getKey(), v);
                }
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode e : node) {
                Object v = toMmdbValue(e);
                if (v != null) {
                    list.add(v);
                }
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return switch (node.numberType()) {
                case INT -> node.intValue();
                case LONG -> toUint64(node.longValue());
                case FLOAT -> node.floatValue();
                case DOUBLE -> node.doubleValue();
                case BIG_INTEGER -> toUint128(node.bigIntegerValue());
                case BIG_DECIMAL -> node.doubleValue();
            };
        }
        return node.asText();
    }

    private static Long toUint64(long v) {
        if (v < 0) {
            throw new IllegalArgumentException("awdb UINT 出现负值: " + v);
        }
        return v;
    }

    private static BigInteger toUint128(BigInteger v) {
        if (v.signum() < 0 || v.bitLength() > 128) {
            throw new IllegalArgumentException("awdb 大整数超出 uint128: " + v);
        }
        return v;
    }
}
