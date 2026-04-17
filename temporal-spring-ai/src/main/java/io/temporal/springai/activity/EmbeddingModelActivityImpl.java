package io.temporal.springai.activity;

import io.temporal.springai.model.EmbeddingModelTypes;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Implementation of {@link EmbeddingModelActivity} that delegates to a Spring AI {@link
 * EmbeddingModel}.
 *
 * <p>This implementation handles the conversion between Temporal-serializable types ({@link
 * EmbeddingModelTypes}) and Spring AI types.
 */
public class EmbeddingModelActivityImpl implements EmbeddingModelActivity {

  private final EmbeddingModel embeddingModel;

  public EmbeddingModelActivityImpl(EmbeddingModel embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  @Override
  public EmbeddingModelTypes.EmbedOutput embed(EmbeddingModelTypes.EmbedTextInput input) {
    float[] embedding = embeddingModel.embed(input.text());
    return new EmbeddingModelTypes.EmbedOutput(embedding);
  }

  @Override
  public EmbeddingModelTypes.EmbedBatchOutput embedBatch(
      EmbeddingModelTypes.EmbedBatchInput input) {
    EmbeddingResponse response = embeddingModel.embedForResponse(input.texts());

    List<EmbeddingModelTypes.EmbeddingResult> results =
        IntStream.range(0, response.getResults().size())
            .mapToObj(
                i -> {
                  var embedding = response.getResults().get(i);
                  return new EmbeddingModelTypes.EmbeddingResult(i, embedding.getOutput());
                })
            .collect(Collectors.toList());

    EmbeddingModelTypes.EmbeddingMetadata metadata = null;
    if (response.getMetadata() != null) {
      var usage = response.getMetadata().getUsage();
      metadata =
          new EmbeddingModelTypes.EmbeddingMetadata(
              response.getMetadata().getModel(),
              usage != null && usage.getTotalTokens() != null
                  ? usage.getTotalTokens().intValue()
                  : null,
              embeddingModel.dimensions());
    }

    return new EmbeddingModelTypes.EmbedBatchOutput(results, metadata);
  }

  @Override
  public EmbeddingModelTypes.DimensionsOutput dimensions() {
    return new EmbeddingModelTypes.DimensionsOutput(embeddingModel.dimensions());
  }
}
