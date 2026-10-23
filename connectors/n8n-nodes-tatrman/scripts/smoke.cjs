// Run inside the pinned n8n image; dependencies come from that runtime, not the development SDK.
const assert = require('node:assert/strict');
const http = require('node:http');
const { CustomDirectoryLoader } = require('n8n-core');
(async () => {
  const loader = new CustomDirectoryLoader('/opt/tatrman');
  await loader.loadAll();
  assert.deepEqual(Object.keys(loader.nodeTypes).sort(), ['tatrmanRun', 'tatrmanTrigger']);
  assert.ok(loader.credentialTypes.tatrmanAutomationApi);
  const run = loader.nodeTypes.tatrmanRun.type;
  let requests = 0;
  const server = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer smoke-token');
    assert.equal(req.url, '/automation/recipes/release_order/runs');
    assert.equal(req.method, 'POST');
    let raw = '';
    req.on('data', chunk => { raw += chunk; });
    req.on('end', () => {
      const body = JSON.parse(raw);
      assert.equal(body.deployment, 'release_native_event');
      assert.deepEqual(body.port, { key: 4711 });
      requests++;
      res.writeHead(201, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ runId: 'smoke-run', replayed: false, status: 'open' }));
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`;
    const parameters = { operation: 'open', recipe: 'release_order', deployment: 'release_native_event', port: '{"key":4711}' };
    const result = await run.execute.call({
      getInputData: () => [{ json: {} }], getNode: () => ({ name: 'Smoke', type: 'tatrmanRun', parameters: {} }),
      getCredentials: async () => ({ baseUrl, token: 'smoke-token' }),
      getNodeParameter: (name, _index, fallback) => parameters[name] ?? fallback,
      continueOnFail: () => false,
      helpers: { async httpRequestWithAuthentication(credential, options) {
        assert.equal(credential, 'tatrmanAutomationApi');
        const credentialType = loader.credentialTypes.tatrmanAutomationApi.type;
        assert.equal(credentialType.authenticate.properties.headers.Authorization, '=Bearer {{$credentials.token}}');
        const response = await fetch(options.url, { method: options.method, headers: { authorization: 'Bearer smoke-token', 'content-type': 'application/json' }, body: JSON.stringify(options.body) });
        return { statusCode: response.status, headers: {}, body: await response.json() };
      } },
    });
    assert.equal(result[0][0].json.runId, 'smoke-run');
    assert.equal(requests, 1);
    console.log('PASS: pinned n8n loader loaded both nodes + credential; Run(open) reached HTTP fake with bearer');
  } finally { server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
