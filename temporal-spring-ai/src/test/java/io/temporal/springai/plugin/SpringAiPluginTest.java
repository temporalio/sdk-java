package io.temporal.springai.plugin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.temporal.springai.activity.ChatModelActivityImpl;
import io.temporal.springai.activity.EmbeddingModelActivityImpl;
import io.temporal.springai.activity.VectorStoreActivityImpl;
import io.temporal.springai.tool.ExecuteToolLocalActivityImpl;
import io.temporal.worker.Worker;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

class SpringAiPluginTest {

  /**
   * Collects all activity implementations registered via
   * worker.registerActivitiesImplementations(). Since the method has varargs (Object...), each
   * invocation may pass one or more objects.
   */
  private List<Object> captureRegisteredActivities(Worker worker) {
    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(worker, atLeastOnce()).registerActivitiesImplementations(captor.capture());
    return captor.getAllValues();
  }

  private Set<Class<?>> activityTypes(List<Object> activities) {
    return activities.stream().map(Object::getClass).collect(Collectors.toSet());
  }

  @Test
  void chatModelOnly_registersChatModelAndExecuteToolLocal() {
    ChatModel chatModel = mock(ChatModel.class);
    Worker worker = mock(Worker.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel, null, null);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));

    assertTrue(
        types.contains(ChatModelActivityImpl.class), "ChatModelActivity should be registered");
    assertTrue(
        types.contains(ExecuteToolLocalActivityImpl.class),
        "ExecuteToolLocalActivity should be registered");
    assertFalse(
        types.contains(VectorStoreActivityImpl.class),
        "VectorStoreActivity should NOT be registered");
    assertFalse(
        types.contains(EmbeddingModelActivityImpl.class),
        "EmbeddingModelActivity should NOT be registered");
  }

  @Test
  void chatModelAndVectorStore_registersVectorStoreActivity() {
    ChatModel chatModel = mock(ChatModel.class);
    VectorStore vectorStore = mock(VectorStore.class);
    Worker worker = mock(Worker.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel, vectorStore, null);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));

    assertTrue(
        types.contains(ChatModelActivityImpl.class), "ChatModelActivity should be registered");
    assertTrue(
        types.contains(ExecuteToolLocalActivityImpl.class),
        "ExecuteToolLocalActivity should be registered");
    assertTrue(
        types.contains(VectorStoreActivityImpl.class), "VectorStoreActivity should be registered");
    assertFalse(
        types.contains(EmbeddingModelActivityImpl.class),
        "EmbeddingModelActivity should NOT be registered");
  }

  @Test
  void chatModelAndEmbeddingModel_registersEmbeddingModelActivity() {
    ChatModel chatModel = mock(ChatModel.class);
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    Worker worker = mock(Worker.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel, null, embeddingModel);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));

    assertTrue(
        types.contains(ChatModelActivityImpl.class), "ChatModelActivity should be registered");
    assertTrue(
        types.contains(ExecuteToolLocalActivityImpl.class),
        "ExecuteToolLocalActivity should be registered");
    assertFalse(
        types.contains(VectorStoreActivityImpl.class),
        "VectorStoreActivity should NOT be registered");
    assertTrue(
        types.contains(EmbeddingModelActivityImpl.class),
        "EmbeddingModelActivity should be registered");
  }

  @Test
  void allBeans_registersAllActivities() {
    ChatModel chatModel = mock(ChatModel.class);
    VectorStore vectorStore = mock(VectorStore.class);
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    Worker worker = mock(Worker.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel, vectorStore, embeddingModel);
    plugin.initializeWorker("test-queue", worker);

    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));

    assertTrue(
        types.contains(ChatModelActivityImpl.class), "ChatModelActivity should be registered");
    assertTrue(
        types.contains(ExecuteToolLocalActivityImpl.class),
        "ExecuteToolLocalActivity should be registered");
    assertTrue(
        types.contains(VectorStoreActivityImpl.class), "VectorStoreActivity should be registered");
    assertTrue(
        types.contains(EmbeddingModelActivityImpl.class),
        "EmbeddingModelActivity should be registered");
  }

  @Test
  void multipleModels_chatModelActivityGetsAllModels() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    Worker worker = mock(Worker.class);

    // Use the multi-model constructor; primaryChatModel=model1 makes "openai" the default
    SpringAiPlugin plugin = new SpringAiPlugin(models, model1, null, null);
    plugin.initializeWorker("test-queue", worker);

    // Verify the plugin exposes both models
    assertEquals(2, plugin.getChatModels().size());
    assertTrue(plugin.getChatModels().containsKey("openai"));
    assertTrue(plugin.getChatModels().containsKey("anthropic"));
    assertSame(model1, plugin.getChatModel("openai"));
    assertSame(model2, plugin.getChatModel("anthropic"));

    // Verify ChatModelActivityImpl was registered
    Set<Class<?>> types = activityTypes(captureRegisteredActivities(worker));
    assertTrue(
        types.contains(ChatModelActivityImpl.class),
        "ChatModelActivity should be registered with multi-model config");
  }

  @Test
  void primaryModel_usedAsDefault() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    // model2 ("anthropic") is the primary
    SpringAiPlugin plugin = new SpringAiPlugin(models, model2, null, null);

    assertEquals("anthropic", plugin.getDefaultModelName());
    assertSame(model2, plugin.getChatModel());
  }

  @Test
  void noPrimaryModel_firstEntryIsDefault() {
    ChatModel model1 = mock(ChatModel.class);
    ChatModel model2 = mock(ChatModel.class);
    Map<String, ChatModel> models = new LinkedHashMap<>();
    models.put("openai", model1);
    models.put("anthropic", model2);

    // No primary model
    SpringAiPlugin plugin = new SpringAiPlugin(models, null, null, null);

    assertEquals("openai", plugin.getDefaultModelName());
    assertSame(model1, plugin.getChatModel());
  }

  @Test
  void singleModelConstructor_usesDefaultModelName() {
    ChatModel chatModel = mock(ChatModel.class);

    SpringAiPlugin plugin = new SpringAiPlugin(chatModel);

    assertEquals(SpringAiPlugin.DEFAULT_MODEL_NAME, plugin.getDefaultModelName());
    assertSame(chatModel, plugin.getChatModel());
  }

  @Test
  void nullChatModelsMap_throwsIllegalArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpringAiPlugin(null, (ChatModel) null, null, null));
  }

  @Test
  void emptyChatModelsMap_throwsIllegalArgument() {
    Map<String, ChatModel> empty = new LinkedHashMap<>();
    assertThrows(IllegalArgumentException.class, () -> new SpringAiPlugin(empty, null, null, null));
  }
}
