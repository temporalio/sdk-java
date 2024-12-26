package io.temporal.workflowcheck.sample.gradlemulti.app;

import io.temporal.workflowcheck.sample.gradlemulti.workflows.MyWorkflow;

public class App {
  public static void main(String[] args) {
    System.out.println("Workflow class: " + MyWorkflow.class);
  }
}