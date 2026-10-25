module: meta-mcp
image: ghcr.io/boraperusic/meta-mcp   # jib
ports: { http: 7262 }
needs:
  downstream: [ metadata ]
wave: 1    # registry/core — MCP edge over the metadata / model-graph service
