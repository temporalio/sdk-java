package io.temporal.workflowcheck;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Configuration for workflow check. See README for configuration format. */
public class Config {
  /** Load the default set of config properties. */
  public static Properties defaultProperties() throws IOException {
    Properties props = new Properties();
    try (InputStream is = Config.class.getResourceAsStream("workflowcheck.properties")) {
      props.load(is);
    }
    return props;
  }

  /**
   * Create a new configuration from the given set of properties. Later properties with the same key
   * overwrite previous ones, but more specific properties apply before less specific ones.
   */
  public static Config fromProperties(Properties... props) {
    return new Config(new DescriptorMatcher("invalid", props));
  }

  final DescriptorMatcher invalidMembers;

  private Config(DescriptorMatcher invalidMembers) {
    this.invalidMembers = invalidMembers;
  }
}
