# hive-cabi

Loads native programs that speak the hive C ABI (`hive-cabi/v1`) into hive IAddons, in-process, on the JVM (Panama FFM) and on Node (koffi).

A native library exports two symbols:

```c
char* hive_call(const char* op, const char* request_json); /* JSON in, {"ok":true,"value":..} | {"ok":false,"error":".."} out */
void  hive_free(char*);
```

and answers the op `ops` with its catalog: `[{"name": .., "doc": .., "pure": ..}]`.

The contract (ports, schemas, the `:native/loader` registry slot) lives in [hive-spi](https://github.com/hive-agi/hive-spi) under `hive-spi.native`. This library is the mechanism:

- an IAddon that provides `:native/loader` and registers a JVM loader into the hive-spi registry;
- a generic program-addon builder: one MCP tool per op from the `ops` catalog;
- resource resolution for native binaries shipped inside a jar at `resources/native/<os>-<arch>/`;
- `npm/`: the Node loader, `@hive-agi/hive-cabi`, with TypeScript types.

A program addon depends on hive-spi only, declares `:addon/requires-capabilities #{:native/loader}`, and reports degraded (never throws) when no loader is mounted.

The JVM needs `--enable-native-access=ALL-UNNAMED` (JDK 22+).

MIT licensed.
