export type ErrorType = 'native/unavailable' | 'native/timeout' | 'native/malformed' | 'native/failed';
export type Envelope<T = unknown> = {ok: true; value: T} | {ok: false; error: string; 'error/type'?: ErrorType};
export interface OpEntry {name: string; doc: string; pure: boolean}
export type Transport = 'node/ffi' | 'node/wasm';
export interface LibrarySpec {
  'native/library': string;
  'native/abi': 'hive-cabi/v1' | ':hive-cabi/v1';
  'native/artifacts': Partial<Record<'linux-x86_64' | 'linux-aarch64' | 'darwin-aarch64' | 'darwin-x86_64' | 'windows-x86_64', {shared: string}>> & {wasm?: {js?: string; wasi?: string; exec?: string}};
  'native/timeout-ms'?: number;
  'native/transports'?: Transport[];
}
export interface NativePort {
  readonly library: string;
  readonly host: Transport | 'native/unavailable';
  call<T = unknown>(op: string, request: Record<string, unknown>): Promise<Envelope<T>>;
  close(): void;
}
export interface NativeLoader {
  transports(): Set<Transport>;
  openPort(spec: LibrarySpec): NativePort;
}
export function createLoader(options?: {workerURL?: URL}): NativeLoader;
export function toolName(library: string, op: string): string;
export function catalogTools(port: NativePort): Promise<Array<{name: string; op: string; description: string; pure: boolean; call(request: Record<string, unknown>): Promise<Envelope>}>>;
