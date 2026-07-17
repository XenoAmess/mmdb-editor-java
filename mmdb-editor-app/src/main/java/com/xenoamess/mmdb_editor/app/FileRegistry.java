package com.xenoamess.mmdb_editor.app;

import com.xenoamess.mmdb_editor.core.MmdbReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web 会话内打开的 mmdb 文件注册表（工作副本模式）。
 * 所有编辑在临时工作副本上进行，保存时原子替换。
 */
final class FileRegistry {

    record Entry(UUID id, Path workFile, Path sourceFile, MmdbReader reader) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Path workDir;

    FileRegistry(Path workDir) throws IOException {
        this.workDir = workDir;
        Files.createDirectories(workDir);
    }

    Entry open(Path source) throws IOException {
        Path work = workDir.resolve(UUID.randomUUID() + ".mmdb");
        Files.copy(source, work);
        return register(work, source);
    }

    Entry upload(String fileName, byte[] content) throws IOException {
        Path work = workDir.resolve(UUID.randomUUID() + "-" + fileName);
        Files.write(work, content);
        return register(work, work);
    }

    private Entry register(Path work, Path source) throws IOException {
        UUID id = UUID.randomUUID();
        Entry e = new Entry(id, work, source, MmdbReader.open(work));
        entries.put(id, e);
        return e;
    }

    Entry get(UUID id) {
        Entry e = entries.get(id);
        if (e == null) {
            throw new IllegalArgumentException("未知文件 id: " + id);
        }
        return e;
    }

    /** 编辑后重建 reader（工作副本已变） */
    Entry refresh(UUID id) throws IOException {
        Entry e = get(id);
        Entry refreshed = new Entry(id, e.workFile(), e.sourceFile(), MmdbReader.open(e.workFile()));
        entries.put(id, refreshed);
        return refreshed;
    }

    /** 保存：工作副本原子写回源路径（若源即工作副本则仅重建 reader） */
    Path save(UUID id) throws IOException {
        Entry e = get(id);
        if (!e.workFile().equals(e.sourceFile())) {
            Path tmp = e.sourceFile().resolveSibling(e.sourceFile().getFileName() + ".tmp");
            Files.copy(e.workFile(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, e.sourceFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        return e.sourceFile();
    }
}
