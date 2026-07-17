package com.xenoamess.mmdb_editor.core;

import com.maxmind.db.Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MmdbReader（自研读取）与 MmdbEditor（流式改写）测试。
 */
class MmdbReaderEditorTest {

    @TempDir
    Path tempDir;

    private static byte[] v4(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private Path sampleV4() throws Exception {
        Path p = tempDir.resolve("sample.mmdb");
        MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .databaseType("sample-db")
                .languages(List.of("zh-CN"))
                .description("zh-CN", "样例库")
                .insert(v4(202, 0, 0, 0), 8, Map.of("country", "中国", "city", "广州"))
                .insert(v4(1, 0, 0, 0), 8, Map.of("country", "美国", "city", "洛杉矶"))
                .write(p);
        return p;
    }

    @Test
    void readerLooksUpAndReadsMetadata() throws Exception {
        MmdbReader reader = MmdbReader.open(sampleV4());
        assertEquals("sample-db", reader.metadata().get("database_type"));
        assertEquals(4, reader.ipVersion());

        Object rec = reader.lookup(InetAddress.getByName("202.96.128.86"));
        assertTrue(rec instanceof Map);
        assertEquals("中国", ((Map<?, ?>) rec).get("country"));
        assertEquals("美国", ((Map<?, ?>) reader.lookup(v4(1, 2, 3, 4))).get("country"));
        assertNull(reader.lookup(v4(8, 8, 8, 8)));
    }

    @Test
    void walkCoversAllPrefixes() throws Exception {
        MmdbReader reader = MmdbReader.open(sampleV4());
        List<String> found = new ArrayList<>();
        reader.walk((address, prefixLength, record) ->
                found.add(prefixLength + ":" + ((Map<?, ?>) record).get("country")));
        assertEquals(2, found.size());
        assertTrue(found.contains("8:中国"));
        assertTrue(found.contains("8:美国"));
    }

    @Test
    void editorModifiesAndDrops() throws Exception {
        Path src = sampleV4();
        Path dst = tempDir.resolve("edited.mmdb");
        MmdbEditor.transform(src, dst, (addr, len, record) -> {
            Map<?, ?> r = (Map<?, ?>) record;
            if ("中国".equals(r.get("country"))) {
                Map<String, Object> changed = new LinkedHashMap<>();
                r.forEach((k, v) -> changed.put(String.valueOf(k), v));
                changed.put("city", "深圳");
                return changed;
            }
            if ("美国".equals(r.get("country"))) {
                return null; // 删除
            }
            return record;
        });

        MmdbReader edited = MmdbReader.open(dst);
        assertEquals("深圳", ((Map<?, ?>) edited.lookup(v4(202, 1, 1, 1))).get("city"));
        assertNull(edited.lookup(v4(1, 1, 1, 1)));
        // metadata 保留
        assertEquals("sample-db", edited.metadata().get("database_type"));

        // oracle 交叉验证
        try (Reader oracle = new Reader(dst.toFile())) {
            assertEquals("深圳", oracle.get(InetAddress.getByName("202.1.1.1"), Map.class).get("city"));
            assertNull(oracle.get(InetAddress.getByName("1.1.1.1"), Map.class));
        }
    }

    @Test
    void editorPreservesIpv4Alias() throws Exception {
        // 构造带 v4 别名的混合库
        Path src = tempDir.resolve("mixed.mmdb");
        byte[] v4mapped = new byte[16];
        System.arraycopy(v4(202, 0, 0, 0), 0, v4mapped, 12, 4);
        byte[] v6 = new byte[16];
        v6[0] = (byte) 0x80;
        MmdbWriter.writer(MmdbWriter.IpVersion.V6_TREE_128)
                .insert(v6, 1, Map.of("kind", "v6"))
                .insert(v4mapped, 96 + 8, Map.of("country", "中国"))
                .aliasIpv4InV6()
                .write(src);

        Path dst = tempDir.resolve("mixed-edited.mmdb");
        MmdbEditor.transform(src, dst, (addr, len, record) -> record);

        // 编辑后 ::ffff: 文本查询仍应命中（别名重建，且未产生重复子树）
        MmdbReader edited = MmdbReader.open(dst);
        assertEquals("中国", ((Map<?, ?>) edited.lookup(InetAddress.getByName("202.96.128.86"))).get("country"));
        try (Reader oracle = new Reader(dst.toFile())) {
            assertEquals("中国", oracle.get(InetAddress.getByName("::ffff:202.96.128.86"), Map.class).get("country"));
            assertEquals("v6", oracle.get(InetAddress.getByName("8001::1"), Map.class).get("kind"));
        }

        // walk 不应因别名重复汇报 v4 前缀
        List<String> countries = new ArrayList<>();
        edited.walk((addr, len, rec) -> {
            Object c = ((Map<?, ?>) rec).get("country");
            if (c != null) {
                countries.add((String) c);
            }
        });
        assertEquals(1, countries.size());
    }
}
