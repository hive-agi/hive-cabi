import {parentPort, workerData} from 'node:worker_threads';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {openFfi} from './ffi.js';

async function openWasm({library, artifact, wasmExec}) {
  const require = createRequire(import.meta.url);
  require(wasmExec);
  const go = new globalThis.Go();
  const {instance} = await WebAssembly.instantiate(readFileSync(artifact), go.importObject);
  go.run(instance).catch(error => parentPort.postMessage({fatal: String(error)}));
  const name = `hive_${library}`;
  if (typeof globalThis[name] !== 'function') throw new Error(`${name} not registered`);
  return {call(op, request) { return JSON.parse(globalThis[name](op, JSON.stringify(request))); }, close() {}};
}

try {
  const port = workerData.transport === 'node/wasm' ? await openWasm(workerData) : openFfi(workerData.library, workerData.artifact);
  parentPort.on('message', ({id, op, request}) => {
    try {
      const envelope = port.call(op, request);
      parentPort.postMessage({id, envelope: typeof envelope?.ok === 'boolean' ? envelope : {ok: false, error: 'Invalid native envelope', 'error/type': 'native/malformed'}});
    } catch (error) {
      parentPort.postMessage({id, envelope: {ok: false, error: String(error), 'error/type': 'native/failed'}});
    }
  });
  parentPort.postMessage({ready: true});
} catch (error) {
  parentPort.postMessage({fatal: String(error)});
}
