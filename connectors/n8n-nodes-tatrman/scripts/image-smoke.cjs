// Run against the derived image with a fresh /home/node/.n8n mount, never a dist bind mount.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { CustomDirectoryLoader } = require('n8n-core');

(async () => {
  assert.equal(process.getuid(), 1000, 'The image must run as the unprivileged node user');
  assert.equal(process.env.N8N_CUSTOM_EXTENSIONS, '/opt/tatrman-nodes');
  const directory = '/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman';
  const pkg = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'));
  assert.equal(pkg.name, '@collite/n8n-nodes-tatrman');
  assert.equal(pkg.version, process.env.EXPECTED_PACKAGE_VERSION);
  const runtime = '/usr/local/lib/node_modules/n8n';
  assert.equal(JSON.parse(fs.readFileSync(`${runtime}/package.json`, 'utf8')).version, '2.44.6');
  const sdk = JSON.parse(fs.readFileSync(`${runtime}/node_modules/n8n-workflow/package.json`, 'utf8'));
  assert.equal(pkg.peerDependencies['n8n-workflow'], sdk.version);
  for (const file of [...pkg.n8n.nodes, ...pkg.n8n.credentials]) {
    assert.ok(fs.statSync(path.join(directory, file)).isFile(), `Missing packaged file: ${file}`);
  }
  const loader = new CustomDirectoryLoader(process.env.N8N_CUSTOM_EXTENSIONS);
  await loader.loadAll();
  assert.equal(loader.packageName, 'CUSTOM');
  assert.deepEqual(Object.keys(loader.nodeTypes).sort(), ['tatrmanRun', 'tatrmanTrigger']);
  assert.ok(loader.credentialTypes.tatrmanAutomationApi);
  console.log(`PASS: image package ${pkg.version}, runtime/SDK, unprivileged user, both nodes and credential survive a fresh PVC mount`);
})().catch(error => { console.error(error); process.exitCode = 1; });
