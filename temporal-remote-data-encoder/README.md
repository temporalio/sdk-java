# Temporal Remote Data Encoder module

## What problems does Remote Data Encoder (RDE) solves?

- Allows reuse of complicated encryption logic written once between different languages
- Allows `tctl` to encode payloads for `tctl workflow start` and Temporal WebUI to decode encrypted payloads
- Allows the creation of a service that has an access to encryption keys for performing the encryption/decryption instead of a developer workstation or service accessing the keys directly.

## How does RDE work?

### RDE Server

- It's an HTTP Server responding to POST requests with 'application/json' content type and sending back responses with 'application/json' content type
- It listens on some base URL which is postfixed with /encode or /decode for encoding and decoding respectively
- Expects and emits `io.temporal.api.common.v1.Payloads` serialized to json format using [Proto3 Json Mapping](https://developers.google.com/protocol-buffers/docs/proto3#json) and sent in HTTP Request / Response body

This module provides two reference implementations useful for creation of RDE servers:
- `io.temporal.rde.httpserver.RDEHttpServer` - standalone simple RDE HTTP Server
- `io.temporal.rde.servlet.RDEServlet4` - Servlet compatible with 3.0 and 4.0 standard Java Servlet Specification that can be deployed to any application or servlet container supporting Servlet Specification of version 3.0 or 4.0

### RDE Codec

Can be used as one of the codecs in `io.temporal.common.converter.CodecDataConverter` configured on `WorkflowClientOptions#dataConverter` to use Remote Data Encoder Server for payloads encoding.
Please keep in mind that this is an optional component. If you use RDE Server to decode data for tctl or WebUI purposes, you don't have to use it for encoding/decoding payloads on Workflow Client or Workers side. They may perform the encoding locally.

This module provides a reference implementation of RDE codec that should be used by users as a base for their own RDE codecs: `io.temporal.payload.codec.AbstractRemoteDataEncoderCodec` by implementing a POST method using the HTTP client of their choice.
This module may supply some standard implementations for popular HTTP Clients, including `io.temporal.payload.codec.OkHttpRemoteDataEncoderCodec` for [OkHttpClient](https://square.github.io/okhttp/).

## Security notes for RDE implementing encryption

Introducing encrypting/decrypting RDE in a system creates a new party with access to the encryption keys and exposes APIs to encode and decode any payloads.
In such a system, anybody who gets access to the HTTP endpoints of RDE can encode and decode any payloads.
Therefore, users have to worry about not just the protection of encryption keys but also unauthorized access to the RDE HTTP endpoints.

On another side, it may significantly reduce the number of parties accessing encryption keys.

Users should evaluate the security tradeoffs of this solution.