import koffi from 'koffi';

/** Open the hive-cabi/v1 symbols; ownership of every returned pointer stays with hive_free. */
export function openFfi(library, artifact) {
  const dll = koffi.load(artifact);
  const call = dll.func('void *hive_call(const char *op, const char *request_json)');
  const free = dll.func('void hive_free(void *result)');
  return {
    library,
    host: 'node/ffi',
    call(op, request) {
      const ptr = call(op, JSON.stringify(request));
      if (ptr == null) return {ok: false, error: 'hive_call returned null', 'error/type': 'native/malformed'};
      try {
        const text = koffi.decode(ptr, 'char', -1);
        const envelope = JSON.parse(text);
        if (typeof envelope?.ok !== 'boolean') throw new Error('invalid envelope');
        return envelope;
      } finally {
        free(ptr);
      }
    },
    close() { dll.unload(); }
  };
}
