package io.temporal.releaseautomation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class ProcessSupportTest {
  @Test
  public void shellScriptsUseExplicitBashAndPortableSeparators() {
    List<String> command = ProcessSupport.bash(Paths.get("trusted\\release-script.sh"));
    assertEquals("bash", command.get(0));
    assertTrue(command.get(1).endsWith("trusted/release-script.sh"));
    assertTrue(!command.get(1).contains("\\"));
  }
}
