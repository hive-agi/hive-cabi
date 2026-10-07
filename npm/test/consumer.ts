import {createLoader, catalogTools, type LibrarySpec} from '../index.js';
const spec: LibrarySpec = {
  'native/library': 'autopdf', 'native/abi': 'hive-cabi/v1',
  'native/artifacts': {'linux-x86_64': {shared: '/tmp/libautopdf.so'}},
  'native/transports': ['node/ffi']
};
const loader = createLoader();
const port = loader.openPort(spec);
const answer = await port.call('ops', {});
if (answer.ok) console.log(answer.value, await catalogTools(port));
port.close();
