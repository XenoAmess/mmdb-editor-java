package com.xenoamess.mmdb_editor.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST API 测试（Quarkus dev services 启动完整应用）。
 */
@QuarkusTest
class ApiResourceTest {

    private static File fixture(String name) {
        URL url = ApiResourceTest.class.getResource("/" + name);
        assertNotNull(url);
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sampleMmdb() throws Exception {
        // 用 core writer 现场构造一个小库
        Path tmp = Files.createTempFile("api-sample", ".mmdb");
        com.xenoamess.mmdb_editor.core.MmdbWriter.writer(com.xenoamess.mmdb_editor.core.MmdbWriter.IpVersion.V4_TREE_32)
                .databaseType("api-test")
                .insert(new byte[]{1, 0, 0, 0}, 8, Map.of("country", "中国", "city", "广州"))
                .insert(new byte[]{(byte) 202, 0, 0, 0}, 8, Map.of("country", "美国", "city", "洛杉矶"))
                .write(tmp);
        return Files.readAllBytes(tmp);
    }

    private String uploadSample() throws Exception {
        Map<String, Object> resp = given()
                .multiPart("file", "sample.mmdb", sampleMmdb(), "application/octet-stream")
                .when().post("/api/files/upload")
                .then().statusCode(200)
                .extract().body().as(Map.class);
        return (String) resp.get("id");
    }

    @Test
    void uploadAndLookup() throws Exception {
        String id = uploadSample();
        given().queryParam("ip", "202.96.128.86")
                .when().get("/api/files/{id}/lookup", id)
                .then().statusCode(200)
                .body("country", org.hamcrest.Matchers.equalTo("美国"));
        given().queryParam("ip", "8.8.8.8")
                .when().get("/api/files/{id}/lookup", id)
                .then().statusCode(404);
    }

    @Test
    void recordsWalkAndEditAndDelete() throws Exception {
        String id = uploadSample();

        // 浏览
        given().when().get("/api/files/{id}/records", id)
                .then().statusCode(200)
                .body("items.size()", org.hamcrest.Matchers.equalTo(2));

        // 编辑：1.2.3.0/24 插入新记录
        given().contentType(ContentType.JSON)
                .body(Map.of("country", "日本", "city", "东京"))
                .when().put("/api/files/{id}/records/1.2.3.0/24", id)
                .then().statusCode(200);
        given().queryParam("ip", "1.2.3.4")
                .when().get("/api/files/{id}/lookup", id)
                .then().statusCode(200)
                .body("city", org.hamcrest.Matchers.equalTo("东京"));
        // 覆盖记录仍连续
        given().queryParam("ip", "1.9.9.9")
                .when().get("/api/files/{id}/lookup", id)
                .then().statusCode(200)
                .body("city", org.hamcrest.Matchers.equalTo("广州"));

        // 删除 202/8 → 向上无覆盖 → 空洞
        given().when().delete("/api/files/{id}/records/202.0.0.0/8", id)
                .then().statusCode(200);
        given().queryParam("ip", "202.96.128.86")
                .when().get("/api/files/{id}/lookup", id)
                .then().statusCode(404);
    }

    @Test
    void convertAwdbToMmdb() throws Exception {
        byte[] awdb = Files.readAllBytes(fixture("test_20260717.awdb").toPath());
        Map<String, Object> job = given()
                .multiPart("file", "test.awdb", awdb, "application/octet-stream")
                .when().post("/api/convert")
                .then().statusCode(200)
                .extract().body().as(Map.class);
        String jobId = (String) job.get("id");

        String mmdbId = null;
        for (int i = 0; i < 50; i++) {
            Map<String, Object> status = given().when().get("/api/convert/{jobId}", jobId)
                    .then().statusCode(200).extract().body().as(Map.class);
            if ("done".equals(status.get("status"))) {
                mmdbId = (String) status.get("mmdbId");
                break;
            }
            if ("error".equals(status.get("status"))) {
                throw new AssertionError("convert error: " + status.get("error"));
            }
            Thread.sleep(100);
        }
        assertNotNull(mmdbId, "转换任务超时");

        given().queryParam("ip", "202.96.128.86")
                .when().get("/api/files/{id}/lookup", mmdbId)
                .then().statusCode(200)
                .body("country", org.hamcrest.Matchers.equalTo("中国"));
    }

    @Test
    void downloadReturnsBytes() throws Exception {
        String id = uploadSample();
        byte[] original = sampleMmdb();
        byte[] downloaded = given().when().get("/api/files/{id}/download", id)
                .then().statusCode(200).extract().body().asByteArray();
        assertTrue(downloaded.length > 100);
        assertEquals(original.length, downloaded.length);
    }
}
