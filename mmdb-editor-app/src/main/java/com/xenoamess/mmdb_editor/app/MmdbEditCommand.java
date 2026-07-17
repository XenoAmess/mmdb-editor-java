package com.xenoamess.mmdb_editor.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xenoamess.mmdb_editor.converter.awdb.AwdbToMmdbConverter;
import com.xenoamess.mmdb_editor.core.MmdbEditor;
import com.xenoamess.mmdb_editor.core.MmdbReader;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import io.quarkus.picocli.runtime.annotations.TopCommand;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * mmdb-edit 单二进制：picocli 子命令树 + serve Web。
 * main() 在 Quarkus.run 前检测 serve 子命令以开启 HTTP 端口。
 */
@QuarkusMain
@TopCommand
@Command(name = "mmdb-edit", mixinStandardHelpOptions = true,
        description = "mmdb 编辑器与转换器（单二进制）",
        subcommands = {
                MmdbEditCommand.InfoCmd.class,
                MmdbEditCommand.LookupCmd.class,
                MmdbEditCommand.DumpCmd.class,
                MmdbEditCommand.SetCmd.class,
                MmdbEditCommand.DeleteCmd.class,
                MmdbEditCommand.ConvertCmd.class,
                MmdbEditCommand.ServeCmd.class,
        })
public class MmdbEditCommand implements QuarkusApplication, Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String... args) {
        // serve 子命令需要 HTTP；其余 CLI 命令不开端口（application.properties 默认 false）
        for (String arg : args) {
            if ("serve".equals(arg)) {
                System.setProperty("quarkus.http.host-enabled", "true");
                break;
            }
        }
        Quarkus.run(MmdbEditCommand.class, args);
    }

    @Override
    public int run(String... args) {
        return new CommandLine(this).execute(args);
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    static String prefixText(byte[] address, int prefixLength) throws Exception {
        return InetAddress.getByAddress(address).getHostAddress() + "/" + prefixLength;
    }

    static byte[] parsePrefix(String text, int expectedBytes) throws Exception {
        int slash = text.lastIndexOf('/');
        String ipPart = slash >= 0 ? text.substring(0, slash) : text;
        byte[] addr = InetAddress.getByName(ipPart).getAddress();
        if (addr.length != expectedBytes) {
            throw new IllegalArgumentException("地址族与库不符（需 " + expectedBytes + " 字节）: " + text);
        }
        return addr;
    }

    static int parsePrefixLen(String text, int defaultLen) {
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? Integer.parseInt(text.substring(slash + 1)) : defaultLen;
    }

    @Command(name = "info", description = "打印 mmdb metadata")
    static class InfoCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "mmdb 文件")
        Path file;

        @Override
        public Integer call() throws Exception {
            MmdbReader reader = MmdbReader.open(file);
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(reader.metadata()));
            return 0;
        }
    }

    @Command(name = "lookup", description = "查询 IP 的记录")
    static class LookupCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "mmdb 文件")
        Path file;
        @Parameters(index = "1", description = "IP 地址")
        String ip;

        @Override
        public Integer call() throws Exception {
            MmdbReader reader = MmdbReader.open(file);
            Object record = reader.lookup(InetAddress.getByName(ip));
            System.out.println(record == null ? "null"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record));
            return 0;
        }
    }

    @Command(name = "dump", description = "全量遍历输出（前缀 记录）")
    static class DumpCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "mmdb 文件")
        Path file;
        @Option(names = "--limit", description = "最多输出条数", defaultValue = "100")
        int limit;

        @Override
        public Integer call() throws Exception {
            MmdbReader reader = MmdbReader.open(file);
            int[] count = {0};
            reader.walk((address, prefixLength, record) -> {
                if (count[0]++ >= limit) {
                    return;
                }
                try {
                    System.out.println(prefixText(address, prefixLength) + "\t"
                            + MAPPER.writeValueAsString(record));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            System.err.println("total (capped at limit): " + Math.min(count[0], limit));
            return 0;
        }
    }

    @Command(name = "set", description = "插入/覆盖一条前缀记录（JSON 值）")
    static class SetCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "源 mmdb 文件")
        Path src;
        @Parameters(index = "1", description = "输出 mmdb 文件")
        Path dst;
        @Parameters(index = "2", description = "前缀，如 1.2.3.0/24 或 ::1/128")
        String prefix;
        @Parameters(index = "3", description = "记录 JSON")
        String recordJson;

        @Override
        public Integer call() throws Exception {
            MmdbReader reader = MmdbReader.open(src);
            int bytes = reader.ipVersion() == 4 ? 4 : 16;
            byte[] address = parsePrefix(prefix, bytes);
            int prefixLength = parsePrefixLen(prefix, bytes * 8);
            Object record = MAPPER.readValue(recordJson, Object.class);
            MmdbEditor.upsert(src, dst, address, prefixLength, record);
            System.out.println("set " + prefixText(address, prefixLength) + " -> " + dst);
            return 0;
        }
    }

    @Command(name = "delete", description = "删除前缀（空间向上合并到覆盖记录）")
    static class DeleteCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "源 mmdb 文件")
        Path src;
        @Parameters(index = "1", description = "输出 mmdb 文件")
        Path dst;
        @Parameters(index = "2", description = "前缀，如 1.2.3.0/24 或 ::1/128")
        String prefix;

        @Override
        public Integer call() throws Exception {
            MmdbReader reader = MmdbReader.open(src);
            int bytes = reader.ipVersion() == 4 ? 4 : 16;
            byte[] address = parsePrefix(prefix, bytes);
            int prefixLength = parsePrefixLen(prefix, bytes * 8);
            MmdbEditor.delete(src, dst, address, prefixLength);
            System.out.println("delete " + prefixText(address, prefixLength) + " -> " + dst);
            return 0;
        }
    }

    @Command(name = "convert", description = "awdb (Gen-B) 转换为 mmdb")
    static class ConvertCmd implements Callable<Integer> {
        @Parameters(index = "0", description = "源 awdb 文件")
        Path awdb;
        @Parameters(index = "1", description = "输出 mmdb 文件")
        Path out;

        @Override
        public Integer call() throws Exception {
            AwdbToMmdbConverter.convert(awdb, out);
            System.out.println("converted " + awdb + " -> " + out);
            return 0;
        }
    }

    @Command(name = "serve", description = "启动 Web UI（Vue SPA + REST API）")
    static class ServeCmd implements Callable<Integer> {
        @Option(names = "--port", description = "HTTP 端口", defaultValue = "8080")
        int port;

        @Override
        public Integer call() {
            System.setProperty("quarkus.http.port", String.valueOf(port));
            System.out.println("mmdb-edit web UI: http://localhost:" + port);
            Quarkus.waitForExit();
            return 0;
        }
    }
}
