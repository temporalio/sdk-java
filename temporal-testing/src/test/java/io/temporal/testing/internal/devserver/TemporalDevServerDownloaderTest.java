package io.temporal.testing.internal.devserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.temporal.testing.TemporalDevServerOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporalDevServerDownloaderTest {
  private static final String BASE_URL_PROPERTY = "io.temporal.testing.devServerDownloadBaseUrl";

  @TempDir Path tempDirectory;
  private HttpServer httpServer;

  @AfterEach
  void tearDown() {
    System.clearProperty(BASE_URL_PROPERTY);
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  @Test
  void extractsRequestedFileFromTarGz() throws Exception {
    byte[] contents = "#!/bin/sh\necho fake\n".getBytes(StandardCharsets.UTF_8);
    Path archive = tempDirectory.resolve("synthetic.tar.gz");
    Files.write(archive, tarGz("nested/temporal", contents));
    Path extracted = tempDirectory.resolve("extracted");

    TemporalDevServerDownloader.extractRequestedFile(archive, "nested/temporal", extracted);

    assertEquals(new String(contents, StandardCharsets.UTF_8), readString(extracted));
  }

  @Test
  void fixedVersionDownloadsOnceAndConcurrentPreparationSharesCache() throws Exception {
    AtomicInteger metadataRequests = new AtomicInteger();
    AtomicInteger archiveRequests = new AtomicInteger();
    byte[] executable = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
    startDownloadServer("fixed-test", executable, metadataRequests, archiveRequests);
    TemporalDevServerOptions options =
        TemporalDevServerOptions.newBuilder()
            .setDownloadVersion("fixed-test")
            .setDownloadDestination(tempDirectory.resolve("cache").toString())
            .build();

    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Path>> calls = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        calls.add(() -> TemporalDevServerDownloader.prepare(options));
      }
      List<Future<Path>> futures = executor.invokeAll(calls);
      Path expected = futures.get(0).get();
      for (Future<Path> future : futures) {
        assertEquals(expected, future.get());
      }
      assertEquals(1, metadataRequests.get());
      assertEquals(1, archiveRequests.get());

      assertEquals(expected, TemporalDevServerDownloader.prepare(options));
      assertEquals(1, metadataRequests.get());
      assertEquals(1, archiveRequests.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void defaultResolutionIncludesSdkAndFixedResolutionDoesNot() throws Exception {
    AtomicInteger defaultRequests = new AtomicInteger();
    AtomicInteger archiveRequests = new AtomicInteger();
    byte[] executable = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext(
        "/cli/default",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          assertTrue(query.contains("sdk-name=sdk-java"));
          assertTrue(query.contains("sdk-version="));
          defaultRequests.incrementAndGet();
          sendJsonMetadata(exchange);
        });
    httpServer.createContext(
        "/archive",
        exchange -> {
          archiveRequests.incrementAndGet();
          send(exchange, 200, tarGz("temporal", executable));
        });
    httpServer.start();
    System.setProperty(BASE_URL_PROPERTY, baseUrl());

    TemporalDevServerDownloader.prepare(
        TemporalDevServerOptions.newBuilder()
            .setDownloadDestination(tempDirectory.resolve("default-cache").toString())
            .build());

    assertEquals(1, defaultRequests.get());
    assertEquals(1, archiveRequests.get());
  }

  @Test
  void downloadDisabledFailsClearlyWhenExecutableIsAbsent() {
    TemporalDevServerOptions options =
        TemporalDevServerOptions.newBuilder()
            .setDownloadVersion("not-present")
            .setDownloadDestination(tempDirectory.resolve("disabled").toString())
            .setDownloadEnabled(false)
            .build();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> TemporalDevServerDownloader.prepare(options));

    assertTrue(failure.getMessage().contains("downloading is disabled"));
    assertTrue(failure.getMessage().contains("not-present"));
  }

  @Test
  void expiredCacheEntryIsDownloadedAgain() throws Exception {
    AtomicInteger metadataRequests = new AtomicInteger();
    AtomicInteger archiveRequests = new AtomicInteger();
    startDownloadServer(
        "ttl-test",
        "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8),
        metadataRequests,
        archiveRequests);
    TemporalDevServerOptions options =
        TemporalDevServerOptions.newBuilder()
            .setDownloadVersion("ttl-test")
            .setDownloadDestination(tempDirectory.resolve("ttl-cache").toString())
            .setDownloadCacheTtl(Duration.ofSeconds(1))
            .build();
    Path executable = TemporalDevServerDownloader.prepare(options);
    Files.setLastModifiedTime(executable, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

    assertEquals(executable, TemporalDevServerDownloader.prepare(options));
    assertEquals(2, metadataRequests.get());
    assertEquals(2, archiveRequests.get());
  }

  @Test
  void cachePathContainsVersionAndPlatform() {
    TemporalDevServerOptions options =
        TemporalDevServerOptions.newBuilder()
            .setDownloadVersion("a/version")
            .setDownloadDestination(tempDirectory.toString())
            .build();
    TemporalDevServerDownloader.Platform platform = TemporalDevServerDownloader.Platform.current();

    Path cache = TemporalDevServerDownloader.cacheDirectory(options, platform);

    assertTrue(cache.toString().contains("a_version"));
    assertTrue(cache.endsWith(platform.classifier()));
  }

  private void startDownloadServer(
      String version,
      byte[] executable,
      AtomicInteger metadataRequests,
      AtomicInteger archiveRequests)
      throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext(
        "/cli/" + version,
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          assertTrue(query.contains("platform="));
          assertTrue(query.contains("arch="));
          assertTrue(!query.contains("sdk-name="));
          metadataRequests.incrementAndGet();
          sendJsonMetadata(exchange);
        });
    httpServer.createContext(
        "/archive",
        exchange -> {
          archiveRequests.incrementAndGet();
          send(exchange, 200, tarGz("temporal", executable));
        });
    httpServer.start();
    System.setProperty(BASE_URL_PROPERTY, baseUrl());
  }

  private void sendJsonMetadata(HttpExchange exchange) throws IOException {
    String json = "{\"archiveUrl\":\"" + baseUrl() + "/archive\",\"fileToExtract\":\"temporal\"}";
    send(exchange, 200, json.getBytes(StandardCharsets.UTF_8));
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + httpServer.getAddress().getPort();
  }

  private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static byte[] tarGz(String name, byte[] contents) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry(name);
      entry.setMode(0755);
      entry.setSize(contents.length);
      tar.putArchiveEntry(entry);
      tar.write(contents);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static String readString(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }
}
