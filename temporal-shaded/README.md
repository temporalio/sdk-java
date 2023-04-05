# Temporal Java SDK and Testing Framework with shaded dependencies

This module provides a single temporal-sdk, temporal-testing, temporal-test-server, temporal-serviceclient modules
with shaded Protobuf 3, gRPC and Guava.

Usage of this module is not recommended, but may be necessary for users with outdated dependencies who can't upgrade for compatibility reasons.
gRPC/netty, Guava and Proto3 are common culprits of such conflicts.

If you are not struggling with dependencies hell, please stick to the regular temporal-sdk and temporal-testing modules.
