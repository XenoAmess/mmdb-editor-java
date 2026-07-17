package com.xenoamess.mmdb_editor.converter.awdb.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 合成 awdb 测试文件生成器。
 *
 * <p>布局（以 AwdbReader/AwdbDataParser 的实现为准）：
 * <pre>
 * [0..1]                meta 长度（大端 2 字节）
 * [2..2+metaLen)        meta JSON（UTF-8）
 * 树区                   nodeCount 个节点，每节点 byteLen*2 字节（左右子节点各 byteLen，大端无符号）
 * 数据区                 起点 baseOffset = 2 + metaLen + nodeCount*byteLen*2
 * </pre>
 * 叶子节点值 = nodeCount + 10 + 记录相对 baseOffset 的偏移（对应 AwdbReader 的
 * pointer = baseOffset + nodeIndex - nodeCount - 10 公式）。
 *
 * <p>main 方法将三个合成文件固化到 src/test/resources：
 * <ul>
 *   <li>test_20260717.awdb —— decode_type=1（结构化），IPv4，含 multiAreas/POINTER/TEXT/UINT</li>
 *   <li>test_20260717_decode2.awdb —— decode_type=2（直解码），IPv4</li>
 *   <li>test_20260717_v6.awdb —— decode_type=2，IPv6</li>
 * </ul>
 */
public final class AwdbTestFixture {

    public static final String STRUCTURED_FILE = "test_20260717.awdb";
    public static final String DIRECT_FILE = "test_20260717_decode2.awdb";
    public static final String V6_FILE = "test_20260717_v6.awdb";
    public static final String MIXED_FILE = "test_20260717_mixed.awdb";
    public static final String TYPES_FILE = "test_20260717_types.awdb";

    private static final int TYPE_ARRAY = 1;
    private static final int TYPE_POINTER = 2;
    private static final int TYPE_STRING = 3;
    private static final int TYPE_TEXT = 4;
    private static final int TYPE_UINT = 5;
    private static final int TYPE_INT = 6;
    private static final int TYPE_FLOAT = 7;
    private static final int TYPE_DOUBLE = 8;

    private AwdbTestFixture() {
    }

    /* ---------------- 数据记录编码（decode_type=1） ---------------- */

    public static byte[] string(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 255) {
            throw new IllegalArgumentException("string too long for 1-byte length: " + s);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_STRING);
        out.write(utf8.length);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    /** TEXT：type + len(长度字段的字节数) + len字节的大端长度 + UTF-8 内容 */
    public static byte[] text(String s, int lenFieldBytes) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_TEXT);
        out.write(lenFieldBytes);
        writeUnsigned(out, utf8.length, lenFieldBytes);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    public static byte[] uint(long value, int valueBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_UINT);
        out.write(valueBytes);
        writeUnsigned(out, value, valueBytes);
        return out.toByteArray();
    }

    /** INT：type + 4 字节大端值（首字节位于控制区 len 位置，见 AwdbDataParser.parseInt） */
    public static byte[] intValue(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_INT);
        writeUnsigned(out, value & 0xFFFFFFFFL, 4);
        return out.toByteArray();
    }

    /** FLOAT：type + 4 字节大端位模式（首字节位于 len 位置） */
    public static byte[] floatValue(float value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_FLOAT);
        writeUnsigned(out, Float.floatToIntBits(value) & 0xFFFFFFFFL, 4);
        return out.toByteArray();
    }

    /** DOUBLE：type + 8 字节大端位模式（首字节位于 len 位置） */
    public static byte[] doubleValue(double value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_DOUBLE);
        writeUnsigned(out, Double.doubleToLongBits(value), 8);
        return out.toByteArray();
    }

    /** POINTER：type + len(偏移字段字节数) + len字节大端偏移（相对 baseOffset） */
    public static byte[] pointer(long baseOffsetRelative, int offsetBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_POINTER);
        out.write(offsetBytes);
        writeUnsigned(out, baseOffsetRelative, offsetBytes);
        return out.toByteArray();
    }

    public static byte[] array(byte[]... elements) {
        if (elements.length > 255) {
            throw new IllegalArgumentException("too many elements");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(TYPE_ARRAY);
        out.write(elements.length);
        for (byte[] e : elements) {
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    /* ---------------- 文件组装 ---------------- */

    /**
     * @param metaJson  meta JSON 文本（UTF-8）
     * @param nodeCount 节点数
     * @param byteLen   每个子节点值的字节数
     * @param children  树内容：nodeCount*2 个子节点值（大端无符号），叶子值须为 nodeCount+10+记录偏移
     * @param records   数据区记录，按顺序紧密排列
     */
    public static byte[] build(String metaJson, int nodeCount, int byteLen, long[] children, byte[]... records) {
        if (children.length != nodeCount * 2) {
            throw new IllegalArgumentException("children length must be nodeCount*2");
        }
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsigned(out, meta.length, 2);
        out.write(meta, 0, meta.length);
        for (long child : children) {
            writeUnsigned(out, child, byteLen);
        }
        for (byte[] record : records) {
            out.write(record, 0, record.length);
        }
        return out.toByteArray();
    }

    /** 叶子节点值：nodeIndex = nodeCount + 10 + 记录偏移（与 AwdbReader 的 pointer 公式互逆） */
    public static long leaf(int nodeCount, int recordOffset) {
        return nodeCount + 10L + recordOffset;
    }

    private static void writeUnsigned(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((int) (value >>> (i * 8)) & 0xFF);
        }
    }

    /* ---------------- 三个固化样本 ---------------- */

    /** 记录 A：结构化样本（202.96.128.86），含 multiAreas */
    static byte[] recStructuredA() {
        return array(
                string("中国"), string("广东"), string("广州"), string("电信"),
                array(
                        array(string("广东"), string("广州"), string("天河")),
                        array(string("广东"), string("深圳"), string("南山"))));
    }

    /** 记录 B：普通样本（1.2.3.4），multiAreas 为空数组 */
    static byte[] recStructuredB() {
        return array(
                string("美国"), string("加利福尼亚"), string("洛杉矶"), string("Comcast"),
                array());
    }

    /** 记录 A 的编码长度（与文件布局同源，供测试定位后续记录偏移） */
    public static int structuredRecALength() {
        return recStructuredA().length;
    }

    public static byte[] structuredV4() {
        // 记录 C：类型覆盖样本（224.0.0.9）：STRING / TEXT / UINT / POINTER(->记录A) / 空数组
        byte[] recC = array(
                string("类型样本"),
                text("这是一段用于覆盖 TEXT 类型的长文本，长度用两个字节的大端无符号数表示。", 2),
                uint(65535, 2),
                pointer(0, 3),
                array());

        byte[] recA = recStructuredA();
        byte[] recB = recStructuredB();

        int nodeCount = 4;
        int offA = 0;
        int offC = recA.length;
        int offB = recA.length + recC.length;
        long[] children = {
                1, 2,                            // node 0: 首位 0 -> node1, 1 -> node2
                leaf(nodeCount, offB), nodeCount, // node 1: 0 -> B, 1 -> 未命中
                nodeCount, 3,                    // node 2: 0 -> 未命中, 1 -> node3
                leaf(nodeCount, offA), leaf(nodeCount, offC) // node 3: 0 -> A, 1 -> C
        };

        String meta = "{"
                + "\"node_count\":" + nodeCount + ","
                + "\"ip_version\":\"4\","
                + "\"decode_type\":1,"
                + "\"byte_len\":4,"
                + "\"languages\":\"CN\","
                + "\"file_name\":\"" + STRUCTURED_FILE + "\","
                + "\"create_time\":\"2026-07-17\","
                + "\"company_id\":\"xenoamess-test\","
                + "\"columns\":[\"country\",\"province\",\"city\",\"isp\",\"multiAreas\",[\"prov\",\"city\",\"district\"]]"
                + "}";
        return build(meta, nodeCount, 4, children, recA, recC, recB);
    }

    public static byte[] directV4() {
        byte[] rec1 = directRecord("中国\t广东\t广州\t电信");
        byte[] rec2 = directRecord("美国\t加州\t山景城\tGoogle");

        int nodeCount = 2;
        long[] children = {
                1, leaf(nodeCount, 0),              // node 0: 0 -> node1, 1 -> rec1
                leaf(nodeCount, rec1.length), nodeCount // node 1: 0 -> rec2, 1 -> 未命中
        };

        String meta = "{"
                + "\"node_count\":" + nodeCount + ","
                + "\"ip_version\":\"4\","
                + "\"decode_type\":2,"
                + "\"byte_len\":4,"
                + "\"languages\":\"CN\","
                + "\"file_name\":\"" + DIRECT_FILE + "\","
                + "\"create_time\":\"2026-07-17\","
                + "\"company_id\":\"xenoamess-test\","
                + "\"columns\":[\"country\",\"province\",\"city\",\"isp\"]"
                + "}";
        return build(meta, nodeCount, 4, children, rec1, rec2);
    }

    public static byte[] directV6() {
        byte[] rec1 = directRecord("中国\t上海\t上海\tIPv6专线");

        int nodeCount = 2;
        long[] children = {
                1, nodeCount,              // node 0: 0 -> node1, 1 -> 未命中
                leaf(nodeCount, 0), nodeCount // node 1: 0 -> rec1, 1 -> 未命中
        };

        String meta = "{"
                + "\"node_count\":" + nodeCount + ","
                + "\"ip_version\":\"6\","
                + "\"decode_type\":2,"
                + "\"byte_len\":4,"
                + "\"languages\":\"CN\","
                + "\"file_name\":\"" + V6_FILE + "\","
                + "\"create_time\":\"2026-07-17\","
                + "\"company_id\":\"xenoamess-test\","
                + "\"columns\":[\"country\",\"province\",\"city\",\"isp\"]"
                + "}";
        return build(meta, nodeCount, 4, children, rec1);
    }

    /** decode_type=2 记录：4 字节大端长度 + tab 分隔 UTF-8 */
    private static byte[] directRecord(String tabJoinedValues) {
        byte[] utf8 = tabJoinedValues.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUnsigned(out, utf8.length, 4);
        out.write(utf8, 0, utf8.length);
        return out.toByteArray();
    }

    /**
     * ip_version=4_6 混合库：树内含 ::ffff:0/96 前缀链（80 个 0 + 16 个 1），
     * v4 记录挂在链尾，v6 记录挂在 8000::/1。
     * 命中：v4 202.96.128.86 / 1.2.3.4，v6 8001::x；未命中：v4 64.x/128.x，v6 ::1。
     */
    public static byte[] mixedV46() {
        byte[] recA = recStructuredA();
        byte[] recB = recStructuredB();
        byte[] recC = array(
                string("中国"), string("上海"), string("上海"), string("IPv6专线"),
                array());

        int nodeCount = 101;
        int offA = 0;
        int offB = recA.length;
        int offC = recA.length + recB.length;
        long[] children = new long[nodeCount * 2];
        // node 0: 0 -> 链, 1 -> v6 记录 C（8000::/1）
        children[0] = 1;
        children[1] = leaf(nodeCount, offC);
        // nodes 1..79: 0 -> 下一链节点, 1 -> 未命中
        for (int i = 1; i < 80; i++) {
            children[i * 2] = i + 1;
            children[i * 2 + 1] = nodeCount;
        }
        // nodes 80..95: 0 -> 未命中, 1 -> 下一链节点
        for (int i = 80; i < 96; i++) {
            children[i * 2] = nodeCount;
            children[i * 2 + 1] = i + 1;
        }
        // node 96: v4 首位 0 -> node97, 1 -> node99
        children[96 * 2] = 97;
        children[96 * 2 + 1] = 99;
        // node 97: 0 -> B, 1 -> 未命中
        children[97 * 2] = leaf(nodeCount, offB);
        children[97 * 2 + 1] = nodeCount;
        // node 98: 未使用占位
        children[98 * 2] = nodeCount;
        children[98 * 2 + 1] = nodeCount;
        // node 99: 0 -> 未命中, 1 -> A
        children[99 * 2] = nodeCount;
        children[99 * 2 + 1] = leaf(nodeCount, offA);
        // node 100: 未使用占位
        children[100 * 2] = nodeCount;
        children[100 * 2 + 1] = nodeCount;

        String meta = "{"
                + "\"node_count\":" + nodeCount + ","
                + "\"ip_version\":\"4_6\","
                + "\"decode_type\":1,"
                + "\"byte_len\":4,"
                + "\"languages\":\"CN\","
                + "\"file_name\":\"" + MIXED_FILE + "\","
                + "\"create_time\":\"2026-07-17\","
                + "\"company_id\":\"xenoamess-test\","
                + "\"columns\":[\"country\",\"province\",\"city\",\"isp\",\"multiAreas\",[\"prov\",\"city\",\"district\"]]"
                + "}";
        return build(meta, nodeCount, 4, children, recA, recB, recC);
    }

    /**
     * 数值类型覆盖库：flat columns（触发 mapKeyValue 的 == 分支），
     * 记录含 INT/FLOAT/DOUBLE/UINT/STRING。任意 IP 均命中同一记录。
     */
    public static byte[] typesV4() {
        byte[] rec = array(
                intValue(-1),
                floatValue(1.5f),
                doubleValue(-2.5),
                uint(65535, 2),
                string("x"));

        int nodeCount = 1;
        long[] children = {leaf(nodeCount, 0), leaf(nodeCount, 0)};

        String meta = "{"
                + "\"node_count\":" + nodeCount + ","
                + "\"ip_version\":\"4\","
                + "\"decode_type\":1,"
                + "\"byte_len\":4,"
                + "\"languages\":\"CN\","
                + "\"file_name\":\"" + TYPES_FILE + "\","
                + "\"create_time\":\"2026-07-17\","
                + "\"company_id\":\"xenoamess-test\","
                + "\"columns\":[\"i\",\"f\",\"d\",\"u\",\"s\"]"
                + "}";
        return build(meta, nodeCount, 4, children, rec);
    }

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "src/test/resources");
        Files.createDirectories(dir);
        write(dir.resolve(STRUCTURED_FILE), structuredV4());
        write(dir.resolve(DIRECT_FILE), directV4());
        write(dir.resolve(V6_FILE), directV6());
        write(dir.resolve(MIXED_FILE), mixedV46());
        write(dir.resolve(TYPES_FILE), typesV4());
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("written " + path + " (" + bytes.length + " bytes)");
    }
}
