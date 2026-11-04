package io.temporal.internal.payload.limits;

/** Which server-enforced size limit a payload field is subject to. */
enum LimitClass {
  /** Subject to the blob (payload) size limit. */
  BLOB,
  /** Subject to the memo size limit. */
  MEMO
}
