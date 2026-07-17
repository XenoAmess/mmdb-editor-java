package com.xenoamess.mmdb_editor.core;

import com.maxmind.db.Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MmdbWriter 与官方 reader（oracle）的往返对拍。
 */
class MmdbWriterOracleTest {

    @TempDir
    Path tempDir;

    private Reader writeAndRead(MmdbWriter.Builder builder) throws Exception {
        Path p = tempDir.resolve("test.mmdb");
        builder.write(p);
        return new Reader(p.toFile());
    }

    private static byte[] v4(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private static Map<String, Object> record(String country, String city) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("country", country);
        r.put("city", city);
        r.put("score", 65535L);
        r.put("ratio", 1.5d);
        r.put("rank", -3);
        r.put("tags", List.of("x", "y"));
        r.put("big", new BigInteger("0102", 16));
        r.put("active", true);
        return r;
    }

    @Test
    void v4TreeRoundTrip() throws Exception {
        MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .databaseType("test-db")
                .description("en", "test database")
                .languages(List.of("en", "zh-CN"))
                .buildEpoch(1700000000L);
        Map<String, Object> recA = record("中国", "广州");
        Map<String, Object> recB = record("美国", "洛杉矶");
        b.insert(v4(202, 0, 0, 0), 8, recA)
                .insert(v4(1, 0, 0, 0), 8, recB);

        try (Reader r = writeAndRead(b)) {
            assertEquals("test-db", r.getMetadata().databaseType());
            assertEquals(4, r.getMetadata().ipVersion());
            assertEquals(1700000000L, r.getMetadata().buildEpoch().longValue());
            assertEquals(List.of("en", "zh-CN"), r.getMetadata().languages());

            Map<?, ?> got = r.get(InetAddress.getByName("202.96.128.86"), Map.class);
            assertEquals("中国", got.get("country"));
            assertEquals("广州", got.get("city"));
            assertEquals(65535, ((Number) got.get("score")).intValue());
            assertEquals(1.5d, ((Number) got.get("ratio")).doubleValue(), 0.0001);
            assertEquals(-3, ((Number) got.get("rank")).intValue());
            assertEquals(List.of("x", "y"), got.get("tags"));
            assertEquals(true, got.get("active"));

            assertEquals("美国", r.get(InetAddress.getByName("1.2.3.4"), Map.class).get("country"));
            // 未命中
            assertNull(r.get(InetAddress.getByName("8.8.8.8"), Map.class));
        }
    }

    @Test
    void v6TreeWithIpv4AliasRoundTrip() throws Exception {
        MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V6_TREE_128)
                .databaseType("test-mixed");
        byte[] v6rec1 = new byte[16];
        v6rec1[0] = (byte) 0x80;
        b.insert(v6rec1, 1, Map.of("kind", "v6-half-1"));
        // v4 数据放 ::/96
        byte[] v4mapped = new byte[16];
        System.arraycopy(v4(202, 0, 0, 0), 0, v4mapped, 12, 4);
        b.insert(v4mapped, 96 + 8, record("中国", "广州"));
        b.aliasIpv4InV6();

        try (Reader r = writeAndRead(b)) {
            assertEquals(6, r.getMetadata().ipVersion());
            // 直接 v4 查询（reader 走 ::/96）
            assertEquals("中国", r.get(InetAddress.getByName("202.96.128.86"), Map.class).get("country"));
            // ::ffff: 文本形式（走别名）
            assertEquals("中国", r.get(InetAddress.getByName("::ffff:202.96.128.86"), Map.class).get("country"));
            // v6
            assertEquals("v6-half-1", r.get(InetAddress.getByName("8001::1"), Map.class).get("kind"));
        }
    }

    @Test
    void recordSharingWritesPointers() throws Exception {
        // 两条前缀共享同一条记录（equals）→ 数据区只存一份 + pointer
        MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32);
        Map<String, Object> shared = record("中国", "广州");
        b.insert(v4(1, 0, 0, 0), 8, shared)
                .insert(v4(2, 0, 0, 0), 8, record("中国", "广州")); // 内容相同的不同对象
        Path p = tempDir.resolve("shared.mmdb");
        byte[] bytes = b.build();
        java.nio.file.Files.write(p, bytes);

        try (Reader r = new Reader(p.toFile())) {
            assertEquals("广州", r.get(InetAddress.getByName("1.1.1.1"), Map.class).get("city"));
            assertEquals("广州", r.get(InetAddress.getByName("2.2.2.2"), Map.class).get("city"));
        }
    }

    @Test
    void allRecordSizesRoundTrip() throws Exception {
        for (int rs : new int[]{24, 28, 32}) {
            MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                    .recordSize(rs)
                    .insert(v4(10, 0, 0, 0), 8, Map.of("x", "y"));
            try (Reader r = writeAndRead(b)) {
                assertEquals(rs, r.getMetadata().recordSize());
                assertEquals("y", r.get(InetAddress.getByName("10.1.2.3"), Map.class).get("x"));
            }
        }
    }

    @Test
    void emptyPrefixRootRecord() throws Exception {
        // /0 记录：全空间命中
        MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .insert(new byte[4], 0, Map.of("default", true));
        try (Reader r = writeAndRead(b)) {
            assertEquals(true, r.get(InetAddress.getByName("8.8.8.8"), Map.class).get("default"));
        }
    }

    @Test
    void geoIpPrefixRejected() {
        MmdbWriter.Builder b = MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.databaseType("GeoIP2-City"));
    }
}
