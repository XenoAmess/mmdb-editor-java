package com.xenoamess.mmdb_editor.converter.awdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.maxmind.db.Reader;
import com.xenoamess.ipplus360.AwdbReader;
import com.xenoamess.ipplus360.enumerate.FileOpenMode;
import com.xenoamess.ipplus360.impl.AwdbCacheImpl;
import com.xenoamess.mmdb_editor.converter.awdb.fixture.AwdbTestFixture;
import com.xenoamess.mmdb_editor.core.MmdbReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * awdb→mmdb 转换端到端对拍：awdb-java 读取 ↔ 转换产物（oracle reader + 自研 MmdbReader）。
 */
class AwdbToMmdbConverterTest {

    @TempDir
    Path tempDir;

    private static File fixture(String name) {
        URL url = AwdbToMmdbConverterTest.class.getResource("/" + name);
        assertNotNull(url);
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awdbLookup(File awdb, String ip) throws IOException {
        try (AwdbReader reader = AwdbReader.open(awdb, new AwdbCacheImpl(), FileOpenMode.MEMORY)) {
            return reader.findIpLocation(ip);
        }
    }

    private Path convert(String fixtureName) throws IOException {
        Path out = tempDir.resolve(fixtureName + ".mmdb");
        AwdbToMmdbConverter.convert(fixture(fixtureName).toPath(), out);
        return out;
    }

    /** 断言 oracle 查询结果与 awdb 查询结果（经值映射）一致 */
    private void assertSameRecord(Path mmdb, String ip, JsonNode expected, String message) throws IOException {
        // awdb 未命中返回空 ObjectNode；canonicalize 后空 map 视为未命中
        Object expectedValue = canonicalize(AwdbToMmdbConverter.toMmdbValue(expected));
        try (Reader oracle = new Reader(mmdb.toFile())) {
            Object actual = canonicalize(oracle.get(InetAddress.getByName(ip), Object.class));
            if (expectedValue == null) {
                assertNull(actual, message);
            } else {
                assertEquals(expectedValue, actual, message);
            }
        }
    }

    /**
     * 规范化比较结构：整数统一 BigInteger（oracle 把 uint64 解为 BigInteger），
     * 浮点统一 double；空 map 归一为 null。
     */
    private static Object canonicalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return null;
            }
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), canonicalize(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(e -> out.add(canonicalize(e)));
            return out;
        }
        if (value instanceof java.math.BigInteger bi) {
            return bi;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return java.math.BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        return value;
    }

    @Test
    void structuredV4Converts() throws IOException {
        File awdb = fixture(AwdbTestFixture.STRUCTURED_FILE);
        Path mmdb = convert(AwdbTestFixture.STRUCTURED_FILE);

        assertSameRecord(mmdb, "202.96.128.86", awdbLookup(awdb, "202.96.128.86"), "记录A");
        assertSameRecord(mmdb, "1.2.3.4", awdbLookup(awdb, "1.2.3.4"), "记录B");
        assertSameRecord(mmdb, "224.0.0.9", awdbLookup(awdb, "224.0.0.9"), "记录C(POINTER)");
        assertSameRecord(mmdb, "64.0.0.1", awdbLookup(awdb, "64.0.0.1"), "未命中");

        try (Reader oracle = new Reader(mmdb.toFile())) {
            assertEquals(4, oracle.getMetadata().ipVersion());
            // multiAreas 结构保真
            Map<?, ?> rec = oracle.get(InetAddress.getByName("202.96.128.86"), Map.class);
            List<?> areas = (List<?>) rec.get("multiAreas");
            assertEquals(2, areas.size());
            assertEquals("天河", ((Map<?, ?>) areas.get(0)).get("district"));
        }
    }

    @Test
    void directDecode2Converts() throws IOException {
        File awdb = fixture(AwdbTestFixture.DIRECT_FILE);
        Path mmdb = convert(AwdbTestFixture.DIRECT_FILE);
        assertSameRecord(mmdb, "202.96.128.86", awdbLookup(awdb, "202.96.128.86"), "decode2记录1");
        assertSameRecord(mmdb, "1.2.3.4", awdbLookup(awdb, "1.2.3.4"), "decode2记录2");
    }

    @Test
    void v6Converts() throws IOException {
        File awdb = fixture(AwdbTestFixture.V6_FILE);
        Path mmdb = convert(AwdbTestFixture.V6_FILE);
        assertSameRecord(mmdb, "::1", awdbLookup(awdb, "::1"), "v6命中");
        assertSameRecord(mmdb, "8001::1", awdbLookup(awdb, "8001::1"), "v6未命中");
        try (Reader oracle = new Reader(mmdb.toFile())) {
            assertEquals(6, oracle.getMetadata().ipVersion());
        }
    }

    @Test
    void mixedV46RerootsAndAliases() throws IOException {
        File awdb = fixture(AwdbTestFixture.MIXED_FILE);
        Path mmdb = convert(AwdbTestFixture.MIXED_FILE);

        // v4 记录被重 rooting 到 ::/96：直接 v4 查询命中
        assertSameRecord(mmdb, "202.96.128.86", awdbLookup(awdb, "202.96.128.86"), "混合库v4记录");
        assertSameRecord(mmdb, "8001::1", awdbLookup(awdb, "8001::1"), "混合库v6记录");

        // 别名：::ffff: 文本形式也命中
        try (Reader oracle = new Reader(mmdb.toFile())) {
            Map<?, ?> rec = oracle.get(InetAddress.getByName("::ffff:202.96.128.86"), Map.class);
            assertEquals("中国", rec.get("country"));
        }
    }

    @Test
    void numericTypesFidelity() throws IOException {
        Path mmdb = convert(AwdbTestFixture.TYPES_FILE);
        try (Reader oracle = new Reader(mmdb.toFile())) {
            Map<?, ?> rec = oracle.get(InetAddress.getByName("203.0.113.7"), Map.class);
            assertEquals(-1, ((Number) rec.get("i")).intValue());
            assertEquals(1.5f, ((Number) rec.get("f")).floatValue(), 0.0001f);
            assertEquals(-2.5d, ((Number) rec.get("d")).doubleValue(), 0.0000001d);
            assertEquals(65535L, ((Number) rec.get("u")).longValue());
            assertEquals("x", rec.get("s"));
        }
    }

    @Test
    void prefixSetAndRecordsIdentical() throws IOException {
        for (String f : new String[]{AwdbTestFixture.STRUCTURED_FILE, AwdbTestFixture.DIRECT_FILE,
                AwdbTestFixture.V6_FILE, AwdbTestFixture.MIXED_FILE, AwdbTestFixture.TYPES_FILE}) {
            File awdb = fixture(f);
            Path mmdb = convert(f);

            // awdb 侧全量前缀（4_6 重 rooting 后）
            String ipVersion;
            TreeSet<String> awdbPrefixes = new TreeSet<>();
            List<Object> awdbValues = new ArrayList<>();
            try (AwdbReader reader = AwdbReader.open(awdb, new AwdbCacheImpl(), FileOpenMode.MEMORY)) {
                ipVersion = reader.getAwdbMetaData().getIpVersion();
                reader.walk((address, prefixLength, record) -> {
                    byte[] addr = address;
                    if ("4_6".equals(ipVersion) && isMapped(address)) {
                        addr = new byte[16];
                        System.arraycopy(address, 12, addr, 12, 4);
                    }
                    awdbPrefixes.add(prefixStr(addr, prefixLength));
                    awdbValues.add(AwdbToMmdbConverter.toMmdbValue(record));
                });
            }

            // mmdb 侧全量前缀（自研 reader walk，别名剪枝）
            TreeSet<String> mmdbPrefixes = new TreeSet<>();
            List<Object> mmdbValues = new ArrayList<>();
            MmdbReader mmdbReader = MmdbReader.open(mmdb);
            mmdbReader.walk((address, prefixLength, record) -> {
                mmdbPrefixes.add(prefixStr(address, prefixLength));
                mmdbValues.add(record);
            });

            assertEquals(awdbPrefixes, mmdbPrefixes, f + " 前缀集合");
            assertEquals(new TreeSet<>(awdbValues.stream().map(Object::toString).toList()),
                    new TreeSet<>(mmdbValues.stream().map(Object::toString).toList()), f + " 记录集合");
        }
    }

    private static boolean isMapped(byte[] address) {
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

    private static String prefixStr(byte[] address, int prefixLength) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < prefixLength; i++) {
            sb.append((address[i / 8] >> (7 - i % 8)) & 1);
        }
        return sb + "/" + prefixLength;
    }
}
