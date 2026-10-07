import {Worker} from 'node:worker_threads';
import {accessSync, constants} from 'node:fs';
import {resolve, dirname, join} from 'node:path';

const failure = (error, type = 'native/unavailable') => ({ok: false, error: String(error), 'error/type': type});
const platform = () => `${{linux: 'linux', darwin: 'darwin', win32: 'windows'}[process.platform] ?? process.platform}-${{x64: 'x86_64', arm64: 'aarch64'}[process.arch] ?? process.arch}`;
const artifact = path => { const file = resolve(path); accessSync(file, constants.R_OK); return file; };
const unavailable = (name, error) => ({library: name, host: 'native/unavailable', async call() { return failure(error); }, close() {}});

/** Open one worker per library; serial FFI calls cannot block the caller's event loop. */
function workerPort(library, transport, path, wasmExec, timeout, workerURL) {
  const worker = new Worker(workerURL, {workerData: {library, transport, artifact: path, wasmExec}, execArgv: process.execArgv.filter(arg => !arg.startsWith('--input-type'))});
  let next = 0, closed = false;
  const pending = new Map();
  const rejectAll = envelope => { for (const {resolve, timer} of pending.values()) { clearTimeout(timer); resolve(envelope); } pending.clear(); };
  worker.on('message', message => {
    if (message.ready) return;
    if (message.fatal) { closed = true; rejectAll(failure(message.fatal)); worker.terminate(); return; }
    const item = pending.get(message.id);
    if (item) { pending.delete(message.id); clearTimeout(item.timer); item.resolve(message.envelope); }
  });
  worker.on('error', error => { closed = true; rejectAll(failure(error)); });
  worker.on('exit', code => { closed = true; rejectAll(failure(`native worker exited (${code})`)); });
  return {
    library, host: transport,
    call(op, request = {}) {
      if (closed) return Promise.resolve(failure('native worker closed'));
      return new Promise(resolve => {
        const id = ++next;
        const ms = Number.isInteger(request.timeout_ms) && request.timeout_ms > 0 ? request.timeout_ms : timeout;
        const timer = setTimeout(() => {
          pending.delete(id);
          closed = true;
          resolve(failure(`${library}: native call timed out after ${ms} ms`, 'native/timeout'));
          rejectAll(failure(`${library}: native worker terminated after timeout`, 'native/timeout'));
          worker.terminate();
        }, ms);
        pending.set(id, {resolve, timer});
        const {timeout_ms, ...payload} = request;
        worker.postMessage({id, op, request: payload});
      });
    },
    close() { if (!closed) { closed = true; rejectAll(failure('native worker closed')); worker.terminate(); } }
  };
}

/** Create a Node INativeLoader analogue; unloadable artifacts return inert ports. */
export function createLoader({workerURL = new URL('./worker.js', import.meta.url)} = {}) {
  return {
    transports() { return new Set(['node/ffi', 'node/wasm']); },
    openPort(spec) {
      const name = spec['native/library'];
      try {
        if (spec['native/abi'] !== 'hive-cabi/v1' && spec['native/abi'] !== ':hive-cabi/v1') throw new Error('Unsupported native ABI');
        const artifacts = spec['native/artifacts'] ?? {};
        const preferences = spec['native/transports'] ?? ['node/ffi', 'node/wasm'];
        const errors = [];
        for (const transport of preferences) {
          try {
            if (transport === 'node/ffi') {
              const shared = artifacts[platform()]?.shared;
              if (!shared) throw new Error(`No shared artifact for ${platform()}`);
              return workerPort(name, transport, artifact(shared), null, spec['native/timeout-ms'] ?? 30000, workerURL);
            }
            if (transport === 'node/wasm') {
              const wasm = artifacts.wasm?.js;
              if (!wasm) throw new Error('No js/wasm artifact');
              return workerPort(name, transport, artifact(wasm), artifact(artifacts.wasm.exec ?? join(dirname(wasm), 'wasm_exec.js')), spec['native/timeout-ms'] ?? 30000, workerURL);
            }
            errors.push(`Unsupported transport ${transport}`);
          } catch (error) { errors.push(String(error)); }
        }
        return unavailable(name, errors.join('; ') || 'No supported native transport');
      } catch (error) { return unavailable(name, error); }
    }
  };
}

/** MCP tool name for a library op: chars outside [A-Za-z0-9_-] become _, at most 64 chars (hive-spi tool-name). */
const TOOL_CHARS = new Set('abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-');
export function toolName(library, op) {
  return Array.from(`${library}_${op}`, c => (TOOL_CHARS.has(c) ? c : '_')).join('').slice(0, 64);
}

/** Project the native ops catalog into one tool per advertised operation. */
export async function catalogTools(port) {
  const catalog = await port.call('ops', {});
  if (!catalog.ok || !Array.isArray(catalog.value)) return [];
  return catalog.value.map(({name, doc, pure}) => ({name: toolName(port.library, name), op: name, description: doc, pure: Boolean(pure), call: request => port.call(name, request)}));
}
