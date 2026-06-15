package io.temporal.payload.storage;

import io.temporal.common.Experimental;

/**
 * Identity of the workflow or activity a payload is being stored on behalf of. Provided on a
 * best-effort basis on the storing side only; some fields may be absent. Implemented by {@link
 * StorageDriverWorkflowInfo} and {@link StorageDriverActivityInfo}.
 */
@Experimental
public interface StorageDriverTargetInfo {}
