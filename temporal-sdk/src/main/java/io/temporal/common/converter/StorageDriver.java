package io.temporal.common.converter;

import io.temporal.common.Experimental;
import java.util.List;

/**
 * Interface for external storage driver implementations. A storage driver is responsible for
 * uploading large payloads to external storage (e.g., S3, GCS) and retrieving them using claim
 * references.
 *
 * <p>The driver's {@link #name()} is stored in claim tokens persisted in workflow history. Changing
 * a driver's name after payloads have been stored will break retrieval of those payloads.
 *
 * @implSpec Implementations must be thread-safe since the SDK may call {@link #store} and {@link
 *     #retrieve} concurrently from multiple workflow task threads.
 * @see ExternalStorage
 * @see StorageDriverClaim
 */
@Experimental
public interface StorageDriver {

  /**
   * Returns a unique string that identifies this specific driver instance. This name is stored in
   * the claim reference in workflow history to route retrieval requests to the correct driver.
   *
   * <p><b>Critical:</b> Changing this after payloads are stored will break retrieval.
   *
   * @return unique driver instance name, never null
   */
  String name();

  /**
   * Returns a string identifying the driver implementation type (e.g., "aws.s3driver"). Must be the
   * same across all instances of the same driver type.
   *
   * @return driver type identifier, never null
   */
  String type();

  /**
   * Uploads payloads to external storage and returns a {@link StorageDriverClaim} for each payload.
   * The claims contain addressing information needed to retrieve the data later.
   *
   * <p>Each element in the returned list corresponds to the payload at the same index in the input
   * list. Each {@code byte[]} in the input is the serialized protobuf {@code Payload} message (not
   * raw user data) — it has already passed through {@code PayloadConverter} and {@code
   * PayloadCodec}.
   *
   * @param context context with identity information about the workflow/activity owning the
   *     payloads
   * @param payloads serialized payload bytes to store; each byte array is one serialized Payload
   *     protobuf
   * @return list of claims, one per input payload, in the same order
   * @throws StorageDriverException if the store operation fails
   */
  List<StorageDriverClaim> store(StorageDriverStoreContext context, List<byte[]> payloads);

  /**
   * Downloads payloads from external storage using the claim references produced by {@link #store}.
   *
   * <p>Each element in the returned list corresponds to the claim at the same index in the input
   * list.
   *
   * @param context context with identity information about the retrieval environment
   * @param claims list of claims identifying the stored payloads
   * @return list of serialized payload bytes, one per input claim, in the same order
   * @throws StorageDriverException if the retrieve operation fails
   */
  List<byte[]> retrieve(StorageDriverRetrieveContext context, List<StorageDriverClaim> claims);
}
