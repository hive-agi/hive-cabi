import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {performance} from 'node:perf_hooks';
import {createLoader, catalogTools, toolName} from '../index.js';
import {openFfi} from '../ffi.js';

const native = process.env.HIVE_POLYGLOT_NATIVE ?? resolve(process.env.HOME, 'PP/hive/hive-polyglot/native');
const artifact = library => resolve(native, `lib${library}.so`);
const spec = library => ({'native/library': library, 'native/abi': 'hive-cabi/v1', 'native/artifacts': {'linux-x86_64': {shared: artifact(library)}}, 'native/transports': ['node/ffi']});
const requests = [
  ['autopdf', 'ops', {}], ['autopdf', 'capabilities', {}],
  ['autopdf', 'audit', {name: 'letter', template: 'Dear delim[[ .name ]], you owe delim[[ .amount ]] by delim[[ .due ]].', variables: {name: 'Ada', amount: ''}}],
  ['autopdf', 'audit', {name: 'waived', template: 'Ref: delim[[ optional .ref "internal letters carry none" ]].', variables: {}}],
  ['autopdf', 'audit', {template: '  '}], ['autopdf', 'no-such-op', {}],
  ['lazywal', 'ops', {}],
  ['lazywal', 'detect_session', {env: {XDG_SESSION_TYPE: 'wayland', XDG_CURRENT_DESKTOP: 'ubuntu:GNOME'}}],
  ['lazywal', 'detect_session', {env: {}}],
  ['lazywal', 'detect_session', {env: {DISPLAY: ':0', HYPRLAND_INSTANCE_SIGNATURE: 'abc'}}]
];
const quote = x => JSON.stringify(x);
function canonical(x) {
  if (Array.isArray(x)) return `[:seq${x.length ? ' ' : ''}${x.map(canonical).join(' ')}]`;
  if (x !== null && typeof x === 'object') return `[:map${Object.entries(x).length ? ' ' : ''}${Object.entries(x).sort(([a], [b]) => (':' + a).localeCompare(':' + b, 'en')).map(([k, v]) => `[:${k} ${canonical(v)}]`).join(' ')}]`;
  return typeof x === 'string' ? quote(x) : String(x);
}

test('portable oracle: ten byte-identical JVM reference lines via koffi workers', async t => {
  if (!['autopdf', 'lazywal'].every(lib => existsSync(artifact(lib)))) {
    t.diagnostic('SKIP missing live native artifacts (card 20261007162504-2033bb50)');
    t.skip(); return;
  }
  const loader = createLoader();
  const ports = Object.fromEntries(['autopdf', 'lazywal'].map(lib => [lib, loader.openPort(spec(lib))]));
  try {
    const lines = [];
    for (const [library, op, request] of requests) lines.push(`[${quote(library)} ${quote(op)} ${canonical(await ports[library].call(op, request))}]`);
    const reference = readFileSync(resolve(native, '../test/resources/oracle_jvm.txt'), 'utf8').trimEnd().split('\n');
    assert.equal(lines.length, 10);
    assert.deepEqual(lines, reference);
    const tools = await catalogTools(ports.autopdf);
    assert.deepEqual(tools.map(x => x.name), ['autopdf_audit', 'autopdf_capabilities', 'autopdf_generate', 'autopdf_ops']);
    assert.equal((await tools[1].call({})).ok, true);
  } finally { Object.values(ports).forEach(port => port.close()); }
});

test('tool names stay inside the MCP charset', () => {
  assert.equal(toolName('photocraft', 'engine.execute'), 'photocraft_engine_execute');
  assert.equal(toolName('vectorcraft', 'ui.tool.list'), 'vectorcraft_ui_tool_list');
  assert.equal(toolName('x', '.'.repeat(100)).length, 64);
});

test('missing library is inert and never throws', async () => {
  const port = createLoader().openPort({...spec('missing'), 'native/artifacts': {}});
  assert.equal((await port.call('ops', {}))['error/type'], 'native/unavailable');
  port.close();
});

test('worker timeout terminates blocked call and returns typed failure', async t => {
  if (!existsSync(artifact('autopdf'))) { t.diagnostic('SKIP missing live native artifacts (card 20261007162504-2033bb50)'); t.skip(); return; }
  const port = createLoader({workerURL: new URL('./block-worker.js', import.meta.url)}).openPort({...spec('autopdf'), 'native/timeout-ms': 60});
  const start = performance.now();
  const result = await port.call('ops', {});
  assert.equal(result['error/type'], 'native/timeout');
  assert.ok(performance.now() - start < 1000);
  assert.equal((await port.call('ops', {}))['error/type'], 'native/unavailable');
  port.close();
});

test('js/wasm fallback answers ops via Go runtime', async t => {
  const wasm = resolve(native, 'autopdf.js.wasm');
  const exec = resolve(native, 'wasm_exec.js');
  if (!existsSync(wasm) || !existsSync(exec)) { t.diagnostic('SKIP missing live native artifacts (card 20261007162504-2033bb50)'); t.skip(); return; }
  const port = createLoader().openPort({'native/library': 'autopdf', 'native/abi': 'hive-cabi/v1', 'native/artifacts': {wasm: {js: wasm, exec}}, 'native/transports': ['node/wasm']});
  try { const answer = await port.call('ops', {}); assert.equal(answer.ok, true); assert.equal(answer.value.length, 4); }
  finally { port.close(); }
});

test('measure in-thread and worker latency (ops; warm calls)', async t => {
  if (!existsSync(artifact('autopdf'))) { t.diagnostic('SKIP missing live native artifacts (card 20261007162504-2033bb50)'); t.skip(); return; }
  const direct = openFfi('autopdf', artifact('autopdf'));
  const worker = createLoader().openPort(spec('autopdf'));
  try {
    await worker.call('ops', {});
    const n = 50;
    let start = performance.now();
    for (let i = 0; i < n; i++) assert.equal(direct.call('ops', {}).ok, true);
    const directMs = (performance.now() - start) / n;
    start = performance.now();
    for (let i = 0; i < n; i++) assert.equal((await worker.call('ops', {})).ok, true);
    const workerMs = (performance.now() - start) / n;
    t.diagnostic(`ops mean n=${n}: direct=${directMs.toFixed(3)}ms worker=${workerMs.toFixed(3)}ms`);
  } finally { direct.close(); worker.close(); }
});
