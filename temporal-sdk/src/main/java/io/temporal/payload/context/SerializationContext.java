package io.temporal.payload.context;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.PayloadConverter;
import io.temporal.payload.codec.PayloadCodec;

/**
 * Temporal SDK may provide a subclass of {@link SerializationContext} during data serialization to
 * identify the context of this serialization. This context is composed in a way to guarantee that a
 * user object, and it's corresponded Payload will always be handled by the serialization and data
 * conversion process with the same exact context.
 *
 * <p>{@link SerializationContext} scope is defined by a Serialization Target. Serialization Target
 * is the lowest actor in the call tree that the serializing / deserializing object or {@link
 * Payload} belongs to. For example:
 *
 * <ul>
 *   <li>Workflow Input and Output parameters will always get {@link WorkflowSerializationContext}
 *       identifying the workflow both on the {@link WorkflowClient} and Workflow Worker sides.
 *   <li>Side Effect output parameters are contextualized with {@link WorkflowSerializationContext}
 *       of the workflow this side effect belongs to.
 *   <li>Activity Input and Output parameters will always get {@link ActivitySerializationContext}
 *       identifying the activity and it's workflow on both Workflow Method and Activity Worker
 *       sides.
 *   <li>Child Workflow Input and Output parameters will always get {@link
 *       WorkflowSerializationContext} identifying the child workflow on both Parent Workflow and
 *       the Child Workflow sides.
 * </ul>
 *
 * <p>Temporal SDK provides {@link SerializationContext} to Data Serialization process through
 * calling {@code withContext} on {@link DataConverter#withContext(SerializationContext)}, {@link
 * PayloadCodec#withContext(SerializationContext)}, and {@link
 * PayloadConverter#withContext(SerializationContext)} and using the modified instance when
 * applicable.
 *
 * <p>Nexus operations inside a workflow do NOT have a {@link WorkflowSerializationContext} because
 * it is not available in the operation handler.
 *
 * <p>Note: Serialization Context is experimental feature, the class and field structure of {@link
 * SerializationContext} objects may change in the future. There may be also situation where the
 * context is expected, but is not currently provided.
 */
@Experimental
public interface SerializationContext {}
