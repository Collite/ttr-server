# Frozen message compatibility fixtures

These pre-semantics protobuf messages came from the model-graph service, formerly `veles` (MR).
Their recorded source paths are historical evidence. Keep the bytes unchanged when
renaming the module or gRPC service: neither message embeds a gRPC method path.
`MetaWireCompatSpec` proves the current message schema still reads them.
