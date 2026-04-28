package io.temporal.client;

import io.temporal.api.enums.v1.NexusOperationIdConflictPolicy;
import io.temporal.api.enums.v1.NexusOperationIdReusePolicy;
import io.temporal.common.SearchAttributes;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

//TODO -- EVAN -- builder for starting a nexus operation
//Look at other builders to see the patterns - ScheduleOptions
//Gets passed to start and execute

public class NexusClientOperationOptions {

    private final String namespace;
    private final List<NexusClientInterceptor> interceptors;


    private NexusClientOperationOptions(String namespace,
                                        List<NexusClientInterceptor> interceptors){
        this.namespace = namespace;
        this.interceptors = interceptors;
    };

    /**
     * Get the namespace this client will operate on.
     *
     * @return Client namespace
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Get the interceptors of this client
     *
     * @return The list of interceptors to use with the client.
     */
    public List<NexusClientInterceptor> getInterceptors() {
        return interceptors;
    }

    public static NexusClientOperationOptions.Builder newBuilder() {
        return new NexusClientOperationOptions.Builder();
    }

    public static NexusClientOperationOptions.Builder newBuilder(NexusClientOperationOptions options) {
        return new NexusClientOperationOptions.Builder(options);
    }


    private static final NexusClientOperationOptions DEFAULT_INSTANCE;
    public static NexusClientOperationOptions getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    static {
        DEFAULT_INSTANCE = NexusClientOperationOptions.newBuilder().build();
    }

    private Duration scheduleToCloseTimeout;
    // private Duration scheduleToStartTimeout;
    // private Duration startToCloseTimeout;
    private String summary;
    private SearchAttributes searchAttributes;
    private NexusOperationIdReusePolicy idReusePolicy;
    private NexusOperationIdConflictPolicy idConflictPolicy;

    // + public getter for each field




    public static class Builder {
        private String namespace;
        private List<NexusClientInterceptor> interceptors = Collections.emptyList();


        private Builder() {}
        // setter for each field
        private Builder(NexusClientOperationOptions options) {
            if (options == null) {
                return;
            }
            namespace = options.namespace;
            interceptors = options.interceptors;
//            dataConverter = options.dataConverter;
//            identity = options.identity;
//            contextPropagators = options.contextPropagators;
//            interceptors = options.interceptors;
//            plugins = options.plugins;
        }
        /** Set the namespace this client will operate on. */
        public NexusClientOperationOptions.Builder setNamespace(String namespace) {
            this.namespace = namespace;
            return this;
        }
        /**
         * Set the interceptors for this client.
         *
         * @param interceptors specifies the list of interceptors to use with the client.
         */
        public NexusClientOperationOptions.Builder setInterceptors(List<NexusClientInterceptor> interceptors) {
            this.interceptors = interceptors;
            return this;
        }
        //TODO - EVAN - look at ScheduleClientOptions.
        //They have dataConverter, identity, contextPropagators, and plugins as well
        public NexusClientOperationOptions build() {
            return new NexusClientOperationOptions(namespace, interceptors);
        }
    }
}
