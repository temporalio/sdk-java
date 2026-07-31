package io.temporal.testing;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Options for a {@link TemporalDevServer}. */
@Experimental
public final class TemporalDevServerOptions {
  private static final TemporalDevServerOptions DEFAULT_INSTANCE = newBuilder().build();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(TemporalDevServerOptions options) {
    return new Builder(options);
  }

  public static TemporalDevServerOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public static final class Builder {
    private String existingPath;
    private String downloadVersion = "default";
    private String downloadDestination;
    private Duration downloadCacheTtl;
    private boolean downloadEnabled = true;
    private String ip = "127.0.0.1";
    private Integer port;
    private String databaseFilename;
    private boolean uiEnabled;
    private Integer uiPort;
    private String logFormat = "pretty";
    private String logLevel = "warn";
    private String workingDirectory;
    private String logFile;
    private Duration startupTimeout = Duration.ofSeconds(60);
    private List<String> extraArgs = new ArrayList<>();

    private Builder() {}

    private Builder(TemporalDevServerOptions options) {
      if (options == null) {
        throw new NullPointerException("options");
      }
      this.existingPath = options.existingPath;
      this.downloadVersion = options.downloadVersion;
      this.downloadDestination = options.downloadDestination;
      this.downloadCacheTtl = options.downloadCacheTtl;
      this.downloadEnabled = options.downloadEnabled;
      this.ip = options.ip;
      this.port = options.port;
      this.databaseFilename = options.databaseFilename;
      this.uiEnabled = options.uiEnabled;
      this.uiPort = options.uiPort;
      this.logFormat = options.logFormat;
      this.logLevel = options.logLevel;
      this.workingDirectory = options.workingDirectory;
      this.logFile = options.logFile;
      this.startupTimeout = options.startupTimeout;
      this.extraArgs = new ArrayList<>(options.extraArgs);
    }

    /** Sets an existing Temporal CLI executable instead of using the download cache. */
    public Builder setExistingPath(@Nullable String existingPath) {
      this.existingPath = existingPath;
      return this;
    }

    /**
     * Sets the CLI version to download. {@code "default"} selects the version associated with the
     * running sdk-java version; any other non-empty value is sent to temporal.download unchanged.
     */
    public Builder setDownloadVersion(String downloadVersion) {
      this.downloadVersion = downloadVersion;
      return this;
    }

    /** Sets the root directory for cached downloads. Defaults to the JVM temporary directory. */
    public Builder setDownloadDestination(@Nullable String downloadDestination) {
      this.downloadDestination = downloadDestination;
      return this;
    }

    /** Sets the maximum age of a cached executable. A null value caches indefinitely. */
    public Builder setDownloadCacheTtl(@Nullable Duration downloadCacheTtl) {
      this.downloadCacheTtl = downloadCacheTtl;
      return this;
    }

    /** Alias for {@link #setDownloadCacheTtl(Duration)}. */
    public Builder setDownloadTtl(@Nullable Duration downloadCacheTtl) {
      return setDownloadCacheTtl(downloadCacheTtl);
    }

    /** Sets whether a missing or expired executable may be downloaded. */
    public Builder setDownloadEnabled(boolean downloadEnabled) {
      this.downloadEnabled = downloadEnabled;
      return this;
    }

    /** Sets the IP address on which the dev server listens. */
    public Builder setIp(String ip) {
      this.ip = ip;
      return this;
    }

    /** Alias for {@link #setIp(String)}. */
    public Builder setBindIp(String ip) {
      return setIp(ip);
    }

    /** Sets the gRPC port. A null value asks the OS for an available port. */
    public Builder setPort(@Nullable Integer port) {
      this.port = port;
      return this;
    }

    /** Sets an SQLite database filename. A null value uses in-memory SQLite. */
    public Builder setDatabaseFilename(@Nullable String databaseFilename) {
      this.databaseFilename = databaseFilename;
      return this;
    }

    /** Sets whether the Temporal UI is enabled. */
    public Builder setUiEnabled(boolean uiEnabled) {
      this.uiEnabled = uiEnabled;
      return this;
    }

    /** Alias for {@link #setUiEnabled(boolean)}. */
    public Builder setUi(boolean uiEnabled) {
      return setUiEnabled(uiEnabled);
    }

    /** Sets the UI port and implicitly enables the UI. */
    public Builder setUiPort(@Nullable Integer uiPort) {
      this.uiPort = uiPort;
      if (uiPort != null) {
        this.uiEnabled = true;
      }
      return this;
    }

    /** Sets the Temporal CLI log format. Defaults to {@code pretty}. */
    public Builder setLogFormat(String logFormat) {
      this.logFormat = logFormat;
      return this;
    }

    /** Sets the Temporal CLI log level. Defaults to {@code warn}. */
    public Builder setLogLevel(String logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    /** Sets the child process working directory. Defaults to the current working directory. */
    public Builder setWorkingDirectory(@Nullable String workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /** Sets a file that receives server output. A null value inherits the parent output. */
    public Builder setLogFile(@Nullable String logFile) {
      this.logFile = logFile;
      return this;
    }

    /** Sets the single timeout used for health and namespace readiness checks. */
    public Builder setStartupTimeout(Duration startupTimeout) {
      this.startupTimeout = startupTimeout;
      return this;
    }

    /** Sets additional arguments appended to the generated {@code server start-dev} command. */
    public Builder setExtraArgs(List<String> extraArgs) {
      if (extraArgs == null) {
        throw new NullPointerException("extraArgs");
      }
      this.extraArgs = new ArrayList<>(extraArgs);
      return this;
    }

    /** Sets additional arguments appended to the generated {@code server start-dev} command. */
    public Builder setExtraArgs(String... extraArgs) {
      if (extraArgs == null) {
        throw new NullPointerException("extraArgs");
      }
      this.extraArgs = new ArrayList<>();
      Collections.addAll(this.extraArgs, extraArgs);
      return this;
    }

    public TemporalDevServerOptions build() {
      requireNonBlank(downloadVersion, "downloadVersion");
      requireNonBlank(ip, "ip");
      validatePort(port, "port");
      validatePort(uiPort, "uiPort");
      requireNonBlank(logFormat, "logFormat");
      requireNonBlank(logLevel, "logLevel");
      if (existingPath != null) {
        requireNonBlank(existingPath, "existingPath");
      }
      if (downloadDestination != null) {
        requireNonBlank(downloadDestination, "downloadDestination");
      }
      if (databaseFilename != null) {
        requireNonBlank(databaseFilename, "databaseFilename");
      }
      if (workingDirectory != null) {
        requireNonBlank(workingDirectory, "workingDirectory");
      }
      if (logFile != null) {
        requireNonBlank(logFile, "logFile");
      }
      if (downloadCacheTtl != null && downloadCacheTtl.isNegative()) {
        throw new IllegalArgumentException("downloadCacheTtl cannot be negative");
      }
      if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
        throw new IllegalArgumentException("startupTimeout must be positive");
      }
      for (String arg : extraArgs) {
        if (arg == null) {
          throw new IllegalArgumentException("extraArgs cannot contain null");
        }
        if (arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
          throw new IllegalArgumentException("extraArgs cannot contain newlines");
        }
      }
      return new TemporalDevServerOptions(this);
    }

    private static void requireNonBlank(String value, String name) {
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException(name + " cannot be blank");
      }
    }

    private static void validatePort(Integer port, String name) {
      if (port != null && (port < 1 || port > 65535)) {
        throw new IllegalArgumentException(name + " must be between 1 and 65535");
      }
    }
  }

  private final String existingPath;
  private final String downloadVersion;
  private final String downloadDestination;
  private final Duration downloadCacheTtl;
  private final boolean downloadEnabled;
  private final String ip;
  private final Integer port;
  private final String databaseFilename;
  private final boolean uiEnabled;
  private final Integer uiPort;
  private final String logFormat;
  private final String logLevel;
  private final String workingDirectory;
  private final String logFile;
  private final Duration startupTimeout;
  private final List<String> extraArgs;

  private TemporalDevServerOptions(Builder builder) {
    this.existingPath = builder.existingPath;
    this.downloadVersion = builder.downloadVersion;
    this.downloadDestination = builder.downloadDestination;
    this.downloadCacheTtl = builder.downloadCacheTtl;
    this.downloadEnabled = builder.downloadEnabled;
    this.ip = builder.ip;
    this.port = builder.port;
    this.databaseFilename = builder.databaseFilename;
    this.uiEnabled = builder.uiEnabled;
    this.uiPort = builder.uiPort;
    this.logFormat = builder.logFormat;
    this.logLevel = builder.logLevel;
    this.workingDirectory = builder.workingDirectory;
    this.logFile = builder.logFile;
    this.startupTimeout = builder.startupTimeout;
    this.extraArgs = Collections.unmodifiableList(new ArrayList<>(builder.extraArgs));
  }

  @Nullable
  public String getExistingPath() {
    return existingPath;
  }

  public String getDownloadVersion() {
    return downloadVersion;
  }

  @Nullable
  public String getDownloadDestination() {
    return downloadDestination;
  }

  @Nullable
  public Duration getDownloadCacheTtl() {
    return downloadCacheTtl;
  }

  /** Alias for {@link #getDownloadCacheTtl()}. */
  @Nullable
  public Duration getDownloadTtl() {
    return downloadCacheTtl;
  }

  public boolean isDownloadEnabled() {
    return downloadEnabled;
  }

  public String getIp() {
    return ip;
  }

  /** Alias for {@link #getIp()}. */
  public String getBindIp() {
    return ip;
  }

  @Nullable
  public Integer getPort() {
    return port;
  }

  @Nullable
  public String getDatabaseFilename() {
    return databaseFilename;
  }

  public boolean isUiEnabled() {
    return uiEnabled;
  }

  @Nullable
  public Integer getUiPort() {
    return uiPort;
  }

  public String getLogFormat() {
    return logFormat;
  }

  public String getLogLevel() {
    return logLevel;
  }

  @Nullable
  public String getWorkingDirectory() {
    return workingDirectory;
  }

  @Nullable
  public String getLogFile() {
    return logFile;
  }

  public Duration getStartupTimeout() {
    return startupTimeout;
  }

  public List<String> getExtraArgs() {
    return extraArgs;
  }
}
