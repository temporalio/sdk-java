package io.temporal.workflowstreams;

import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.PayloadConverter;
import io.temporal.internal.common.converter.TemporalTransferTypeDataConverter;

/**
 * Builds the converter used for individual Workflow Stream items.
 *
 * <p>Items are serialized into the stream protocol envelope, which is then sent through a Temporal
 * signal or update. That outer envelope already uses the workflow client's or worker's configured
 * data converter and payload codecs. Applying codecs to an item here would encode it a second time
 * and prevent subscribers from decoding the raw item payload. This converter therefore includes
 * only the configured payload converters, while still applying SDK-managed transfer conversion.
 */
final class WorkflowStreamDataConverter {
  private WorkflowStreamDataConverter() {}

  static DataConverter create(PayloadConverter[] payloadConverters) {
    DataConverter converter =
        payloadConverters.length == 0
            ? DefaultDataConverter.STANDARD_INSTANCE
            : new DefaultDataConverter(payloadConverters);
    return TemporalTransferTypeDataConverter.wrap(converter);
  }
}
