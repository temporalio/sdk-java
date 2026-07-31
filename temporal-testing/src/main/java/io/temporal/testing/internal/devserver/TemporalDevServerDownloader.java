package io.temporal.testing.internal.devserver;

import com.google.gson.Gson;
import io.temporal.serviceclient.Version;
import io.temporal.testing.TemporalDevServerOptions;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

/** Internal downloader and executable cache for the Temporal CLI. */
public final class TemporalDevServerDownloader {
  private static final String DOWNLOAD_BASE_URL_PROPERTY =
      "io.temporal.testing.devServerDownloadBaseUrl";
  private static final String DEFAULT_DOWNLOAD_BASE_URL = "https://temporal.download";
  // FileLock coordinates separate JVMs; this monitor prevents overlapping locks within one JVM.
  private static final ConcurrentMap<String, Object> JVM_LOCKS = new ConcurrentHashMap<>();

  private TemporalDevServerDownloader() {}

  public static Path prepare(TemporalDevServerOptions options) {
    String existingPath = options.getExistingPath();
    if (existingPath != null) {
      Path executable = new File(existingPath).toPath().toAbsolutePath().normalize();
      if (!Files.isRegularFile(executable)) {
        throw new IllegalStateException(
            "Temporal CLI executable does not exist or is not a file: " + executable);
      }
      if (!isWindows() && !Files.isExecutable(executable)) {
        throw new IllegalStateException("Temporal CLI executable is not executable: " + executable);
      }
      return executable;
    }

    Platform platform = Platform.current();
    Path cacheDirectory = cacheDirectory(options, platform);
    Path executable = cacheDirectory.resolve(platform.executableName);
    Object jvmLock =
        JVM_LOCKS.computeIfAbsent(
            cacheDirectory.toAbsolutePath().normalize().toString(), ignored -> new Object());
    synchronized (jvmLock) {
      try {
        Files.createDirectories(cacheDirectory);
        Path lockPath = cacheDirectory.resolve(".download.lock");
        try (FileChannel lockChannel =
                FileChannel.open(
                    lockPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            FileLock ignored = lockChannel.lock()) {
          if (isUsableCacheEntry(executable, options.getDownloadCacheTtl())) {
            return executable;
          }
          if (!options.isDownloadEnabled()) {
            throw new IllegalStateException(
                "Temporal CLI "
                    + options.getDownloadVersion()
                    + " for "
                    + platform.classifier()
                    + " is not cached at "
                    + executable
                    + " and downloading is disabled.");
          }
          Files.deleteIfExists(executable);
          DownloadInfo info = getDownloadInfo(options, platform);
          downloadAndExtract(info, executable, cacheDirectory);
          return executable;
        }
      } catch (IOException e) {
        throw new IllegalStateException(
            "Failed preparing Temporal CLI " + options.getDownloadVersion(), e);
      }
    }
  }

  static Path cacheDirectory(TemporalDevServerOptions options, Platform platform) {
    String destination = options.getDownloadDestination();
    Path root =
        destination == null
            ? new File(System.getProperty("java.io.tmpdir"), "temporal-dev-server").toPath()
            : new File(destination).toPath();
    String version =
        "default".equals(options.getDownloadVersion())
            ? "default-sdk-java-" + safePathPart(Version.LIBRARY_VERSION)
            : safePathPart(options.getDownloadVersion());
    return root.toAbsolutePath().normalize().resolve(version).resolve(platform.classifier());
  }

  private static boolean isUsableCacheEntry(Path executable, Duration ttl) throws IOException {
    if (!Files.isRegularFile(executable)) {
      return false;
    }
    if (!isWindows() && !Files.isExecutable(executable)) {
      return false;
    }
    if (ttl == null) {
      return true;
    }
    long ageMillis =
        Math.max(0, System.currentTimeMillis() - Files.getLastModifiedTime(executable).toMillis());
    return ageMillis <= ttl.toMillis();
  }

  private static DownloadInfo getDownloadInfo(TemporalDevServerOptions options, Platform platform)
      throws IOException {
    String version = encodeQueryValue(options.getDownloadVersion()).replace("+", "%20");
    StringBuilder url =
        new StringBuilder(
            System.getProperty(DOWNLOAD_BASE_URL_PROPERTY, DEFAULT_DOWNLOAD_BASE_URL)
                + "/cli/"
                + version
                + "?platform="
                + encodeQueryValue(platform.platform)
                + "&arch="
                + encodeQueryValue(platform.architecture)
                + "&format=tar.gz");
    if ("default".equals(options.getDownloadVersion())) {
      url.append("&sdk-name=sdk-java");
      url.append("&sdk-version=").append(encodeQueryValue(Version.LIBRARY_VERSION));
    }
    HttpURLConnection connection = openFollowingRedirects(url.toString());
    try {
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        throw new IOException(
            "temporal.download returned HTTP " + status + " for " + connection.getURL());
      }
      try (InputStreamReader reader =
          new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
        DownloadInfo info = new Gson().fromJson(reader, DownloadInfo.class);
        if (info == null || isBlank(info.archiveUrl) || isBlank(info.fileToExtract)) {
          throw new IOException("temporal.download returned incomplete download metadata");
        }
        return info;
      }
    } finally {
      connection.disconnect();
    }
  }

  private static void downloadAndExtract(DownloadInfo info, Path executable, Path cacheDirectory)
      throws IOException {
    Path archive =
        cacheDirectory.resolve("archive-" + UUID.randomUUID().toString() + ".downloading");
    Path extracted =
        cacheDirectory.resolve(executable.getFileName() + "." + UUID.randomUUID() + ".extracting");
    try {
      HttpURLConnection connection = openFollowingRedirects(info.archiveUrl);
      try {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
          throw new IOException(
              "CLI archive download returned HTTP " + status + " for " + connection.getURL());
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
            OutputStream output =
                new BufferedOutputStream(new FileOutputStream(archive.toFile()))) {
          copy(input, output);
        }
      } finally {
        connection.disconnect();
      }

      extractRequestedFile(archive, info.fileToExtract, extracted);
      if (!isWindows() && !extracted.toFile().setExecutable(true, true)) {
        throw new IOException("Unable to make Temporal CLI executable: " + extracted);
      }
      atomicMove(extracted, executable);
    } finally {
      Files.deleteIfExists(archive);
      Files.deleteIfExists(extracted);
    }
  }

  static void extractRequestedFile(Path archive, String requestedName, Path destination)
      throws IOException {
    try (BufferedInputStream input =
        new BufferedInputStream(new FileInputStream(archive.toFile()))) {
      input.mark(4);
      int first = input.read();
      int second = input.read();
      input.reset();
      if (first == 'P' && second == 'K') {
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(input)) {
          extractEntry(zip, requestedName, destination);
        }
      } else {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(input))) {
          extractEntry(tar, requestedName, destination);
        }
      }
    }
  }

  private static void extractEntry(
      org.apache.commons.compress.archivers.ArchiveInputStream<?> archive,
      String requestedName,
      Path destination)
      throws IOException {
    String normalizedRequested = normalizeArchiveName(requestedName);
    ArchiveEntry entry;
    while ((entry = archive.getNextEntry()) != null) {
      if (!entry.isDirectory()
          && normalizeArchiveName(entry.getName()).equals(normalizedRequested)) {
        try (OutputStream output =
            new BufferedOutputStream(new FileOutputStream(destination.toFile()))) {
          copy(archive, output);
        }
        return;
      }
    }
    throw new IOException("CLI archive did not contain " + requestedName);
  }

  private static String normalizeArchiveName(String name) {
    String normalized = name.replace('\\', '/');
    while (normalized.startsWith("./")) {
      normalized = normalized.substring(2);
    }
    return normalized;
  }

  private static HttpURLConnection openFollowingRedirects(String url) throws IOException {
    String next = url;
    for (int redirects = 0; redirects <= 5; redirects++) {
      HttpURLConnection connection = (HttpURLConnection) URI.create(next).toURL().openConnection();
      connection.setConnectTimeout(15_000);
      connection.setReadTimeout(60_000);
      connection.setRequestProperty("Accept", "application/json, application/octet-stream");
      connection.setRequestProperty("User-Agent", "temporal-sdk-java/" + Version.LIBRARY_VERSION);
      connection.setInstanceFollowRedirects(false);
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_MOVED_PERM
          && status != HttpURLConnection.HTTP_MOVED_TEMP
          && status != HttpURLConnection.HTTP_SEE_OTHER
          && status != 307
          && status != 308) {
        return connection;
      }
      String location = connection.getHeaderField("Location");
      if (location == null) {
        return connection;
      }
      next = URI.create(next).resolve(location).toString();
      connection.disconnect();
    }
    throw new IOException("Too many redirects downloading " + url);
  }

  private static void atomicMove(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) != -1) {
      output.write(buffer, 0, read);
    }
  }

  private static String encodeQueryValue(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private static String safePathPart(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static final class DownloadInfo {
    private String archiveUrl;
    private String fileToExtract;
  }

  static final class Platform {
    private final String platform;
    private final String architecture;
    private final String executableName;

    private Platform(String platform, String architecture, String executableName) {
      this.platform = platform;
      this.architecture = architecture;
      this.executableName = executableName;
    }

    static Platform current() {
      String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
      String platform;
      String executableName;
      if (os.contains("mac") || os.contains("darwin")) {
        platform = "darwin";
        executableName = "temporal";
      } else if (os.contains("windows")) {
        platform = "windows";
        executableName = "temporal.exe";
      } else if (os.contains("linux")) {
        platform = "linux";
        executableName = "temporal";
      } else {
        throw new IllegalStateException("Unsupported operating system: " + os);
      }

      String machine = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
      String architecture;
      if (machine.equals("x86_64") || machine.equals("amd64")) {
        architecture = "amd64";
      } else if (machine.equals("aarch64") || machine.equals("arm64")) {
        architecture = "arm64";
      } else {
        throw new IllegalStateException("Unsupported architecture: " + machine);
      }
      return new Platform(platform, architecture, executableName);
    }

    String classifier() {
      return platform + "_" + architecture;
    }
  }
}
