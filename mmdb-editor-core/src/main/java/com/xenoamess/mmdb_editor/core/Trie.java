package com.xenoamess.mmdb_editor.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * mmdb 前缀树构建器。子节点槽语义：null=空（未命中），Integer=子节点索引，
 * 其他对象=记录叶子（按值相等比较）。支持：
 * <ul>
 *   <li>插入（前缀不重叠；重叠抛 IllegalStateException）</li>
 *   <li>同值子节点合并压缩（left==right 的节点被短路）</li>
 *   <li>DAG：别名槽可直接引用另一节点（如 ::ffff:0/96 → v4 子树根）</li>
 *   <li>BFS 重编号，输出可达节点表</li>
 * </ul>
 */
final class Trie {

    static final class Node {
        Object left;
        Object right;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final int bitLength;

    Trie(int bitLength) {
        if (bitLength != 32 && bitLength != 128) {
            throw new IllegalArgumentException("bitLength 仅支持 32/128: " + bitLength);
        }
        this.bitLength = bitLength;
        nodes.add(new Node()); // node 0 = root
    }

    int bitLength() {
        return bitLength;
    }

    /** 空标记（写入时序列化为 nodeCount，即"未命中"）；用于在覆盖前缀上打空洞 */
    static final Object EMPTY = new Object();

    /**
     * 插入一条前缀。更深前缀穿过已有记录时自动分裂叶子（原记录覆盖其余空间）。
     * 精确深度处允许覆盖占位叶子；与真实子树重叠则抛 IllegalStateException。
     */
    void insert(byte[] address, int prefixLength, Object record) {
        Objects.requireNonNull(record, "record");
        checkAddress(address, prefixLength);
        int cur = 0;
        for (int i = 0; i < prefixLength; i++) {
            int bit = (address[i / 8] >> (7 - i % 8)) & 1;
            Node n = nodes.get(cur);
            Object slot = bit == 0 ? n.left : n.right;
            if (slot == null) {
                int next = nodes.size();
                nodes.add(new Node());
                if (bit == 0) {
                    n.left = next;
                } else {
                    n.right = next;
                }
                cur = next;
            } else if (slot instanceof Integer idx) {
                cur = idx;
            } else {
                // 叶子分裂：记录/空标记先铺满两侧，继续下钻
                int next = nodes.size();
                Node child = new Node();
                child.left = slot;
                child.right = slot;
                nodes.add(child);
                if (bit == 0) {
                    n.left = next;
                } else {
                    n.right = next;
                }
                cur = next;
            }
        }
        Node n = nodes.get(cur);
        if (!isOverwritable(n)) {
            throw new IllegalStateException("前缀与已有子树重叠，深度 " + prefixLength);
        }
        n.left = record;
        n.right = record;
    }

    /** 在指定前缀处打"未命中"空洞（如删除覆盖前缀的一部分） */
    void insertEmpty(byte[] address, int prefixLength) {
        insert(address, prefixLength, EMPTY);
    }

    private void checkAddress(byte[] address, int prefixLength) {
        if (address.length != bitLength / 8) {
            throw new IllegalArgumentException("address 长度与树位长不符: " + address.length);
        }
        if (prefixLength < 0 || prefixLength > bitLength) {
            throw new IllegalArgumentException("prefixLength 越界: " + prefixLength);
        }
    }

    /**
     * 节点是否可被覆盖写入：空节点或占位叶子（左右相同且非节点引用）。
     * 含真实子树（引用不同）或别名占位（左右相同的节点引用）不可覆盖。
     */
    private static boolean isOverwritable(Node n) {
        if (n.left == null && n.right == null) {
            return true;
        }
        if (n.left == null || !n.left.equals(n.right)) {
            return false;
        }
        return !(n.left instanceof Integer);
    }

    /**
     * 沿路径返回目标节点索引（用于别名）。路径必须已存在且终点是中间节点或空。
     *
     * @return 路径终点节点索引；路径不存在返回 -1
     */
    int findNode(byte[] address, int prefixLength) {
        int cur = 0;
        for (int i = 0; i < prefixLength; i++) {
            int bit = (address[i / 8] >> (7 - i % 8)) & 1;
            Object slot = bit == 0 ? nodes.get(cur).left : nodes.get(cur).right;
            if (!(slot instanceof Integer idx)) {
                return -1;
            }
            cur = idx;
        }
        return cur;
    }

    /**
     * 在指定前缀处写别名（槽直接引用目标节点索引，形成 DAG）。
     * 路径不存在时创建中间节点。
     */
    void alias(byte[] address, int prefixLength, int targetNode) {
        int cur = 0;
        for (int i = 0; i < prefixLength; i++) {
            int bit = (address[i / 8] >> (7 - i % 8)) & 1;
            Node n = nodes.get(cur);
            Object slot = bit == 0 ? n.left : n.right;
            if (slot == null) {
                int next = nodes.size();
                nodes.add(new Node());
                if (bit == 0) {
                    n.left = next;
                } else {
                    n.right = next;
                }
                cur = next;
            } else if (slot instanceof Integer idx) {
                cur = idx;
            } else {
                throw new IllegalStateException("别名路径与已有记录重叠，深度 " + i);
            }
        }
        Node n = nodes.get(cur);
        if (n.left != null || n.right != null) {
            throw new IllegalStateException("别名目标位置非空");
        }
        n.left = targetNode;
        n.right = targetNode;
    }

    /**
     * 压缩：left==right 的节点被其同值子短路。单趟链式解析。
     */
    void compress() {
        int size = nodes.size();
        Object[] replacement = new Object[size];
        for (int i = 0; i < size; i++) {
            Node n = nodes.get(i);
            if (n.left != null && n.left.equals(n.right)) {
                replacement[i] = n.left;
            }
        }
        // 链式解析最终替代值
        for (Node n : nodes) {
            n.left = resolve(n.left, replacement);
            n.right = resolve(n.right, replacement);
        }
    }

    private static Object resolve(Object slot, Object[] replacement) {
        Set<Integer> seen = new HashSet<>();
        while (slot instanceof Integer idx && replacement[idx] != null && seen.add(idx)) {
            slot = replacement[idx];
        }
        return slot;
    }

    /**
     * BFS 重编号可达节点（DAG 感知）。
     *
     * @return 新顺序下的节点表（索引 = 新编号），槽内节点引用已改写为新编号
     */
    List<Node> renumber() {
        Map<Integer, Integer> newIds = new HashMap<>();
        List<Node> ordered = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        newIds.put(0, 0);
        queue.add(0);
        while (!queue.isEmpty()) {
            int old = queue.poll();
            Node n = nodes.get(old);
            Node copy = new Node();
            copy.left = remap(n.left, newIds, queue);
            copy.right = remap(n.right, newIds, queue);
            ordered.add(copy);
        }
        return ordered;
    }

    private static Object remap(Object slot, Map<Integer, Integer> newIds, Deque<Integer> queue) {
        if (slot instanceof Integer old) {
            return newIds.computeIfAbsent(old, k -> {
                int id = newIds.size();
                queue.add(old);
                return id;
            });
        }
        return slot;
    }
}
