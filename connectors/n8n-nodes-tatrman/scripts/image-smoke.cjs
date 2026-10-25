// Run against the derived image with a fresh /home/node/.n8n mount, never a dist bind mount.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { PackageDirectoryLoader } = require('n8n-core');

(async () => {
  assert.equal(process.getuid(), 1000, 'The image must run as the unprivileged node user');
  assert.ok(!process.env.N8N_CUSTOM_EXTENSIONS, 'Use package discovery, not the CUSTOM namespace');
  assert.equal(fs.realpathSync('/home/node/.n8n/nodes/node_modules/@collite/n8n-nodes-tatrman'), '/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman');
  const directory = '/opt/tatrman-nodes/node_modules/@collite/n8n-nodes-tatrman';
  const pkg = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'));
  assert.equal(pkg.name, '@collite/n8n-nodes-tatrman');
  assert.equal(pkg.version, process.env.EXPECTED_PACKAGE_VERSION);
  const runtime = '/usr/local/lib/node_modules/n8n';
  assert.equal(JSON.parse(fs.readFileSync(`${runtime}/package.json`, 'utf8')).version, process.env.EXPECTED_RUNTIME_VERSION);
  const sdk = JSON.parse(fs.readFileSync(`${runtime}/node_modules/n8n-workflow/package.json`, 'utf8'));
  assert.equal(pkg.peerDependencies['n8n-workflow'], sdk.version);
  for (const file of [...pkg.n8n.nodes, ...pkg.n8n.credentials]) {
    assert.ok(fs.statSync(path.join(directory, file)).isFile(), `Missing packaged file: ${file}`);
  }
  const loader = new PackageDirectoryLoader(directory);
  await loader.loadAll();
  assert.equal(loader.packageName, '@collite/n8n-nodes-tatrman');
  assert.deepEqual(Object.keys(loader.nodeTypes).sort(), ['tatrmanRun', 'tatrmanTrigger']);
  assert.ok(loader.credentialTypes.tatrmanAutomationApi);
  console.log(`PASS: image package ${pkg.version}, runtime/SDK, unprivileged user, both nodes and credential survive a fresh PVC mount`);
})().catch(error => { console.error(error); process.exitCode = 1; });
