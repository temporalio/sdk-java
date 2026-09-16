package io.temporal.functional.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.reflect.TypeToken;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.common.converter.PayloadConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.Rule;
import org.junit.Test;

public class SerializationTypeHintTest {
  private static final Type ANIMALS_TYPE = new TypeToken<List<Animal>>() {}.getType();

  private final TypeRecordingPayloadConverter recordingConverter =
      new TypeRecordingPayloadConverter();
  private final DataConverter dataConverter =
      DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(recordingConverter);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setDataConverter(dataConverter).build())
          .setWorkflowTypes(TestWorkflowImpl.class, ChildWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void typedCallersPassSerializationTypeHints() {
    TestWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    List<Animal> initial = Collections.singletonList(new Cat("initial"));
    List<Animal> updated = Collections.singletonList(new Cat("updated"));

    WorkflowClient.start(workflow::run, initial);
    SDKTestWorkflowRule.waitForOKQuery(WorkflowStub.fromTyped(workflow));

    assertEquals(initial, workflow.echo(initial));
    assertEquals(updated, workflow.update(updated));
    workflow.finish(updated);
    assertEquals(updated, WorkflowStub.fromTyped(workflow).getResult(List.class, ANIMALS_TYPE));

    assertTrue(recordingConverter.receivedTypes.size() >= 11);
    assertTrue(recordingConverter.receivedTypes.stream().allMatch(ANIMALS_TYPE::equals));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    List<Animal> run(List<Animal> animals);

    @QueryMethod
    List<Animal> echo(List<Animal> animals);

    @UpdateMethod
    List<Animal> update(List<Animal> animals);

    @SignalMethod
    void finish(List<Animal> animals);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private final TestActivities activities =
        Workflow.newActivityStub(
            TestActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
    private final ChildWorkflow child =
        Workflow.newChildWorkflowStub(
            ChildWorkflow.class, ChildWorkflowOptions.newBuilder().build());
    private List<Animal> animals;
    private boolean finished;

    @Override
    public List<Animal> run(List<Animal> animals) {
      this.animals = animals;
      Workflow.await(() -> finished);
      return activities.echo(child.run(this.animals));
    }

    @Override
    public List<Animal> echo(List<Animal> animals) {
      return animals;
    }

    @Override
    public List<Animal> update(List<Animal> animals) {
      this.animals = animals;
      return animals;
    }

    @Override
    public void finish(List<Animal> animals) {
      this.animals = animals;
      this.finished = true;
    }
  }

  @ActivityInterface
  public interface TestActivities {
    @ActivityMethod
    List<Animal> echo(List<Animal> animals);
  }

  public static class TestActivitiesImpl implements TestActivities {
    @Override
    public List<Animal> echo(List<Animal> animals) {
      return animals;
    }
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    List<Animal> run(List<Animal> animals);
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public List<Animal> run(List<Animal> animals) {
      return animals;
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(@JsonSubTypes.Type(value = Cat.class, name = "cat"))
  public interface Animal {}

  public static class Cat implements Animal {
    private String name;

    public Cat() {}

    public Cat(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Cat && name.equals(((Cat) o).name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }

  private static final class TypeRecordingPayloadConverter implements PayloadConverter {
    private final PayloadConverter delegate = new JacksonJsonPayloadConverter();
    private final ConcurrentLinkedQueue<Type> receivedTypes = new ConcurrentLinkedQueue<>();

    @Override
    public String getEncodingType() {
      return delegate.getEncodingType();
    }

    @Override
    public Optional<Payload> toData(Object value) throws DataConverterException {
      return delegate.toData(value);
    }

    @Override
    public Optional<Payload> toData(Object value, Type valueType) throws DataConverterException {
      receivedTypes.add(valueType);
      return delegate.toData(value, valueType);
    }

    @Override
    public <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType)
        throws DataConverterException {
      return delegate.fromData(content, valueType, valueGenericType);
    }
  }
}
