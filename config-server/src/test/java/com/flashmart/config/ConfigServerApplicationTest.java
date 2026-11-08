package com.flashmart.config;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server boots against a LOCAL file:// git repo (never GitHub, no network)
 * and serves one client's values, named by spring.application.name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

    static Path repo;
    static Path basedir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        repo = Files.createTempDirectory("fm-config-repo");
        basedir = Files.createTempDirectory("fm-config-base");
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repo.resolve("demo-service.yml"), "demo:\n  timeout-ms: 1234\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("one value").setAuthor("t", "t@t").setCommitter("t", "t@t")
                    .setSign(false).call();
        }
        r.add("spring.cloud.config.server.git.uri", () -> repo.toUri().toString());
        r.add("spring.cloud.config.server.git.basedir", () -> basedir.toString());
    }

    @LocalServerPort
    int port;

    @Test
    void servesTheValuesOfOneClientFromTheLocalGitRepo() throws IOException, InterruptedException {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/demo-service/default/main"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("demo.timeout-ms").contains("1234");
    }
}
