package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;

import java.io.*;

public class ActivityIdWithAttempt {

    private final ActivityId activityId;
    private final int attempt;

    ActivityIdWithAttempt(ActivityId activityId, int attempt) {
        this.activityId = activityId;
        this.attempt = attempt;
    }

    public ActivityId getActivityId() {
        return activityId;
    }

    public long getAttempt() {
        return attempt;
    }

    @Override
    public String toString() {
        return "ActivityIdWithAttempt{"
                + "activityId="
                + activityId
                + ", attempt="
                + attempt
                + '}';
    }

    /** Used for task tokens. */
    public ByteString toBytes() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bout);
        try {
            activityId.writeBytes(out);
            out.writeInt(attempt);
            return ByteString.copyFrom(bout.toByteArray());
        } catch (IOException e) {
            throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
        }
    }

    static ActivityIdWithAttempt fromBytes(ByteString serialized) {
        return fromBytes(serialized.toByteArray());
    }

    static ActivityIdWithAttempt fromBytes(byte[] serialized) {
        ByteArrayInputStream bin = new ByteArrayInputStream(serialized);
        DataInputStream in = new DataInputStream(bin);
        try {
            ActivityId activityId = ActivityId.readBytes(in);
            int attempt = in.readInt();
            return new ActivityIdWithAttempt(activityId, attempt);
        } catch (IOException e) {
            throw Status.INTERNAL.withCause(e).withDescription(e.getMessage()).asRuntimeException();
        }
    }
}
