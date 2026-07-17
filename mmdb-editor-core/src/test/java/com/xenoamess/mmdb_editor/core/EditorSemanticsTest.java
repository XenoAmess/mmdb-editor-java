package com.xenoamess.mmdb_editor.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Trie 叶子分裂与 MmdbEditor upsert/delete 语义测试。
 */
class EditorSemanticsTest {

    @TempDir
    Path tempDir;

    private static byte[] v4(int a, int b, int c, int d) {
        return new byte[]{(byte) a, (byte) b, (byte) c, (byte) d};
    }

    private Path base() throws Exception {
        Path p = tempDir.resolve("base.mmdb");
        MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .insert(v4(1, 0, 0, 0), 8, Map.of("net", "one"))
                .write(p);
        return p;
    }

    @Test
    void deeperInsertSplitsCoverRecord() throws Exception {
        // /8 覆盖下插入 /16：分裂后 /16 为新记录，其余 /8 空间仍为原记录
        Path dst = tempDir.resolve("split.mmdb");
        MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .insert(v4(1, 0, 0, 0), 8, Map.of("net", "one"))
                .insert(v4(1, 2, 0, 0), 16, Map.of("net", "one-two"))
                .write(dst);
        MmdbReader r = MmdbReader.open(dst);
        assertEquals("one-two", ((Map<?, ?>) r.lookup(v4(1, 2, 3, 4))).get("net"));
        assertEquals("one", ((Map<?, ?>) r.lookup(v4(1, 3, 3, 4))).get("net"));
    }

    @Test
    void upsertReplacesExactAndInsertsNew() throws Exception {
        Path dst = tempDir.resolve("up.mmdb");
        // 替换精确前缀
        MmdbEditor.upsert(base(), dst, v4(1, 0, 0, 0), 8, Map.of("net", "replaced"));
        assertEquals("replaced", ((Map<?, ?>) MmdbReader.open(dst).lookup(v4(1, 9, 9, 9))).get("net"));

        // 覆盖前缀内插入更深前缀
        Path dst2 = tempDir.resolve("up2.mmdb");
        MmdbEditor.upsert(dst, dst2, v4(1, 2, 0, 0), 16, Map.of("net", "sub"));
        MmdbReader r2 = MmdbReader.open(dst2);
        assertEquals("sub", ((Map<?, ?>) r2.lookup(v4(1, 2, 0, 1))).get("net"));
        assertEquals("replaced", ((Map<?, ?>) r2.lookup(v4(1, 3, 0, 1))).get("net"));
    }

    @Test
    void deleteExactAndPunchHole() throws Exception {
        // 先建 /8 + /16
        Path dst = tempDir.resolve("del-base.mmdb");
        MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .insert(v4(1, 0, 0, 0), 8, Map.of("net", "one"))
                .insert(v4(1, 2, 0, 0), 16, Map.of("net", "one-two"))
                .write(dst);

        // 删除精确 /16 → 回落到 /8 记录
        Path d1 = tempDir.resolve("del1.mmdb");
        MmdbEditor.delete(dst, d1, v4(1, 2, 0, 0), 16);
        MmdbReader r1 = MmdbReader.open(d1);
        assertEquals("one", ((Map<?, ?>) r1.lookup(v4(1, 2, 0, 1))).get("net"));

        // 在 /8 覆盖上打 /24 空洞 → 该范围未命中，其余仍命中
        Path d2 = tempDir.resolve("del2.mmdb");
        MmdbEditor.delete(dst, d2, v4(1, 5, 6, 0), 24);
        MmdbReader r2 = MmdbReader.open(d2);
        assertNull(r2.lookup(v4(1, 5, 6, 7)));
        assertEquals("one", ((Map<?, ?>) r2.lookup(v4(1, 9, 9, 9))).get("net"));
        assertEquals("one-two", ((Map<?, ?>) r2.lookup(v4(1, 2, 0, 1))).get("net"));
    }

    @Test
    void oracleCrossCheckAfterSplit() throws Exception {
        Path dst = tempDir.resolve("split-oracle.mmdb");
        MmdbWriter.writer(MmdbWriter.IpVersion.V4_TREE_32)
                .insert(v4(1, 0, 0, 0), 8, Map.of("net", "one"))
                .insert(v4(1, 2, 0, 0), 16, Map.of("net", "one-two"))
                .write(dst);
        try (com.maxmind.db.Reader oracle = new com.maxmind.db.Reader(dst.toFile())) {
            assertEquals("one-two", oracle.get(InetAddress.getByName("1.2.3.4"), Map.class).get("net"));
            assertEquals("one", oracle.get(InetAddress.getByName("1.3.3.4"), Map.class).get("net"));
        }
    }
}
