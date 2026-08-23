package io.temporal.worker;

import static org.junit.Assert.assertThrows;

import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerFactoryValidationTest {

  private TestWorkflowEnvironment env;

  @Before
  public void setUp() {
    env = TestWorkflowEnvironment.newInstance();
  }

  @After
  public void tearDown() {
    env.close();
  }

  @Test
  public void newWorkerRejectsNullTaskQueue() {
    assertThrows(IllegalArgumentException.class, () -> env.getWorkerFactory().newWorker(null));
  }

  @Test
  public void newWorkerRejectsEmptyTaskQueue() {
    assertThrows(IllegalArgumentException.class, () -> env.getWorkerFactory().newWorker(""));
  }

  @Test
  public void newWorkerRejectsBlankTaskQueue() {
    assertThrows(IllegalArgumentException.class, () -> env.getWorkerFactory().newWorker("   "));
  }
}
