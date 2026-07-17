package com.xenoamess.mmdb_editor.app;

import com.xenoamess.mmdb_editor.converter.awdb.AwdbToMmdbConverter;
import com.xenoamess.mmdb_editor.core.MmdbEditor;
import com.xenoamess.mmdb_editor.core.MmdbReader;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * mmdb-edit Web REST API。
 */
@jakarta.ws.rs.Path("/api")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public class ApiResource {

    private final FileRegistry registry;

    public ApiResource() throws IOException {
        this.registry = new FileRegistry(Path.of(System.getProperty("java.io.tmpdir"), "mmdb-edit-work"));
    }

    /* ---------------- 文件管理 ---------------- */

    public record OpenRequest(String path) {
    }

    public record FileInfo(String id, String path, Map<String, Object> metadata) {
    }

    @jakarta.ws.rs.Path("/files/open")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public FileInfo open(OpenRequest req) throws IOException {
        FileRegistry.Entry e = registry.open(Path.of(req.path()));
        return new FileInfo(e.id().toString(), e.sourceFile().toString(), e.reader().metadata());
    }

    @jakarta.ws.rs.Path("/files/upload")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public FileInfo upload(@RestForm("file") FileUpload file) throws IOException {
        String name = file.fileName() != null ? file.fileName() : "upload.mmdb";
        FileRegistry.Entry e = registry.upload(name, Files.readAllBytes(file.uploadedFile()));
        return new FileInfo(e.id().toString(), e.sourceFile().toString(), e.reader().metadata());
    }

    @jakarta.ws.rs.Path("/files/{id}/download")
    @GET
    @Produces("application/octet-stream")
    public Response download(@RestPath UUID id) throws IOException {
        FileRegistry.Entry e = registry.get(id);
        return Response.ok(Files.readAllBytes(e.workFile()))
                .header("Content-Disposition", "attachment; filename=\"" + e.sourceFile().getFileName() + "\"")
                .build();
    }

    @jakarta.ws.rs.Path("/files/{id}/save")
    @POST
    public Map<String, String> save(@RestPath UUID id) throws IOException {
        Path saved = registry.save(id);
        registry.refresh(id);
        return Map.of("saved", saved.toString());
    }

    /* ---------------- 查询 ---------------- */

    @jakarta.ws.rs.Path("/files/{id}/lookup")
    @GET
    public Object lookup(@RestPath UUID id, @QueryParam("ip") String ip) throws IOException {
        if (ip == null || ip.isBlank()) {
            throw new WebApplicationException("ip 参数必填", 400);
        }
        FileRegistry.Entry e = registry.get(id);
        Object record = e.reader().lookup(InetAddress.getByName(ip.trim()));
        if (record == null) {
            throw new WebApplicationException("未命中: " + ip, 404);
        }
        return record;
    }

    /* ---------------- 记录浏览与编辑 ---------------- */

    public record RecordsPage(List<Map<String, Object>> items, int returned) {
    }

    @jakarta.ws.rs.Path("/files/{id}/records")
    @GET
    public RecordsPage records(@RestPath UUID id,
                               @QueryParam("offset") @DefaultValue("0") int offset,
                               @QueryParam("limit") @DefaultValue("50") int limit) {
        FileRegistry.Entry e = registry.get(id);
        List<Map<String, Object>> items = new ArrayList<>();
        int[] skipped = {0};
        e.reader().walk((address, prefixLength, record) -> {
            if (items.size() >= limit) {
                return;
            }
            if (skipped[0]++ < offset) {
                return;
            }
            try {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("prefix", InetAddress.getByAddress(address).getHostAddress() + "/" + prefixLength);
                item.put("record", record);
                items.add(item);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        return new RecordsPage(items, items.size());
    }

    public record RecordUpsert(Object record, String ip, Integer prefixLength) {
    }

    @jakarta.ws.rs.Path("/files/{id}/records/{prefix:.*}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, String> upsertRecord(@RestPath UUID id, @RestPath String prefix, Object record)
            throws IOException {
        FileRegistry.Entry e = registry.get(id);
        int bytes = e.reader().ipVersion() == 4 ? 4 : 16;
        byte[] address = parseAddress(prefix, bytes);
        int len = parseLen(prefix, bytes * 8);
        Path tmp = e.workFile().resolveSibling(e.workFile().getFileName() + ".edit");
        MmdbEditor.upsert(e.workFile(), tmp, address, len, record);
        Files.move(tmp, e.workFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        registry.refresh(id);
        return Map.of("status", "ok");
    }

    @jakarta.ws.rs.Path("/files/{id}/records/{prefix:.*}")
    @DELETE
    public Map<String, String> deleteRecord(@RestPath UUID id, @RestPath String prefix) throws IOException {
        FileRegistry.Entry e = registry.get(id);
        int bytes = e.reader().ipVersion() == 4 ? 4 : 16;
        byte[] address = parseAddress(prefix, bytes);
        int len = parseLen(prefix, bytes * 8);
        Path tmp = e.workFile().resolveSibling(e.workFile().getFileName() + ".edit");
        MmdbEditor.delete(e.workFile(), tmp, address, len);
        Files.move(tmp, e.workFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        registry.refresh(id);
        return Map.of("status", "ok");
    }

    private static byte[] parseAddress(String prefix, int expectedBytes) {
        try {
            int slash = prefix.lastIndexOf('/');
            String ip = slash >= 0 ? prefix.substring(0, slash) : prefix;
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (addr.length != expectedBytes) {
                throw new WebApplicationException("地址族与库不符: " + prefix, 400);
            }
            return addr;
        } catch (Exception ex) {
            throw new WebApplicationException("前缀非法: " + prefix, 400);
        }
    }

    private static int parseLen(String prefix, int defaultLen) {
        int slash = prefix.lastIndexOf('/');
        return slash >= 0 ? Integer.parseInt(prefix.substring(slash + 1)) : defaultLen;
    }

    /* ---------------- 转换 ---------------- */

    public record ConvertJob(String id, String status, String mmdbId, String error) {
    }

    private final Map<String, ConvertJob> convertJobs = new LinkedHashMap<>();

    @jakarta.ws.rs.Path("/convert")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public ConvertJob convert(@RestForm("file") FileUpload awdb) throws IOException {
        String jobId = UUID.randomUUID().toString();
        ConvertJob job = new ConvertJob(jobId, "running", null, null);
        convertJobs.put(jobId, job);
        byte[] content = Files.readAllBytes(awdb.uploadedFile());
        Thread.startVirtualThread(() -> {
            try {
                Path tmpIn = Files.createTempFile("awdb-upload", ".awdb");
                Files.write(tmpIn, content);
                Path tmpOut = Files.createTempFile("awdb-out", ".mmdb");
                AwdbToMmdbConverter.convert(tmpIn, tmpOut);
                FileRegistry.Entry e = registry.upload("converted.mmdb", Files.readAllBytes(tmpOut));
                convertJobs.put(jobId, new ConvertJob(jobId, "done", e.id().toString(), null));
            } catch (Exception ex) {
                convertJobs.put(jobId, new ConvertJob(jobId, "error", null, ex.getMessage()));
            }
        });
        return job;
    }

    @jakarta.ws.rs.Path("/convert/{jobId}")
    @GET
    public ConvertJob convertStatus(@RestPath String jobId) {
        ConvertJob job = convertJobs.get(jobId);
        if (job == null) {
            throw new WebApplicationException("未知任务: " + jobId, 404);
        }
        return job;
    }
}
