// Run in n8nio/n8n:2.44.6 with --network none. Uses real n8n persistence and execution;
// only the Automation service is fake. All data and credentials are temporary.
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { createHmac, randomUUID } = require('node:crypto');
const { mkdtemp, writeFile, rm } = require('node:fs/promises');
const http = require('node:http');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

async function main() {
  const directory = await mkdtemp(join(tmpdir(), 'tatrman-workflow-'));
  const hooks = new Map();
  const calls = [];
  const failures = [];
  const runs = new Map([['source-run', { status: 'closed', keys: [4711, 4712] }]]);
  let freshRuns = 0;
  let runtime;
  let runtimeLog = '';
  let env;
  const fake = http.createServer(async (req, res) => {
    try {
      assert.equal(req.headers.authorization, 'Bearer local-workflow-token');
      let raw = '';
      for await (const chunk of req) raw += chunk;
      const body = raw ? JSON.parse(raw) : undefined;
      const url = new URL(req.url, 'http://fake');
      calls.push({ method: req.method, path: url.pathname, body, query: Object.fromEntries(url.searchParams) });
      const runId = url.pathname.split('/')[3];
      const run = runs.get(runId);
      let result;
      if (url.pathname === '/automation/webhooks' && req.method === 'GET') result = { webhooks: [...hooks.keys()].map(id => ({ id })) };
      else if (url.pathname === '/automation/webhooks' && req.method === 'POST') {
        assert.equal(body.deployment, 'release_native_event');
        assert.deepEqual(body.kinds, ['run_closed']);
        assert.match(body.secret, /^[a-f0-9]{64}$/);
        const id = `hook-${hooks.size + 1}`;
        hooks.set(id, body);
        result = { id };
        res.statusCode = 201;
      } else if (url.pathname.startsWith('/automation/webhooks/') && req.method === 'DELETE') {
        assert.ok(hooks.delete(url.pathname.split('/').pop()));
        result = {};
      } else if (url.pathname === '/automation/recipes/release_order/runs') {
        assert.equal(req.method, 'POST');
        assert.equal(body.deployment, 'release_native_event');
        assert.equal(body.dryRun, true);
        const id = `fresh-${++freshRuns}`;
        runs.set(id, { status: 'open', keys: body.port.keys ?? [body.port.key] });
        result = { runId: id, status: 'open', replayed: false };
        res.statusCode = 201;
      } else if (url.pathname.endsWith('/evaluate') && run) {
        assert.equal(req.method, 'POST');
        assert.equal(run.status, 'open');
        run.status = 'evaluated';
        result = { verdict: { routes: { release: { rowCount: run.keys.length } }, none: 0 }, guards: [] };
      } else if (url.pathname.endsWith('/routes/release') && run) {
        assert.equal(req.method, 'GET');
        assert.ok(['evaluated', 'closed'].includes(run.status));
        const offset = Number(url.searchParams.get('offset'));
        assert.ok(offset >= 0 && offset < run.keys.length);
        result = { rows: [{ key: run.keys[offset], verdict_reason: 'fixture' }], rowCount: run.keys.length, truncated: offset + 1 < run.keys.length };
      } else if (url.pathname.endsWith('/actions/notify/perform') && run) {
        assert.equal(req.method, 'POST');
        assert.equal(run.status, 'evaluated', 'Never perform new work on the sealed source run');
        assert.ok(body.keys.length === 1 && run.keys.includes(body.keys[0]));
        result = { performed: [{ key: body.keys[0], batchRows: 1 }], skipped: [] };
      } else if (url.pathname.endsWith('/actions/notify/outcome') && run) {
        assert.equal(req.method, 'POST');
        assert.equal(run.status, 'evaluated');
        assert.deepEqual(body.outcomes, [{ key: body.outcomes[0].key, status: 'delivered', at: '2026-10-23T00:00:00Z' }]);
        assert.ok([4711, 4712].includes(body.outcomes[0].key));
        result = {};
      } else if (url.pathname.endsWith('/close') && run) {
        assert.equal(req.method, 'POST');
        assert.notEqual(runId, 'source-run');
        run.status = 'closed';
        result = { status: 'closed', protocolRef: 'fixture-protocol' };
      } else throw new Error(`Unexpected request ${req.method} ${req.url}`);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(result));
    } catch (error) {
      failures.push(error);
      res.writeHead(500, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { code: 'fake-assertion', message: error.message } }));
    }
  });
  await new Promise(resolve => fake.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${fake.address().port}`;
  const n8nUrl = 'http://127.0.0.1:5678';
  env = {
    ...process.env, N8N_USER_FOLDER: directory, N8N_ENCRYPTION_KEY: 'ephemeral-local-workflow-key',
    N8N_CUSTOM_EXTENSIONS: '/opt/tatrman', N8N_LISTEN_ADDRESS: '127.0.0.1', N8N_PORT: '5678',
    N8N_WEBHOOK_URL: `${n8nUrl}/`, N8N_SECURE_COOKIE: 'false', N8N_DIAGNOSTICS_ENABLED: 'false',
    N8N_VERSION_NOTIFICATIONS_ENABLED: 'false', N8N_TEMPLATES_ENABLED: 'false', N8N_RUNNERS_MODE: 'external',
    N8N_RUNNERS_AUTH_TOKEN: 'ephemeral-runner-token-no-code-nodes-used',
    N8N_SSRF_ALLOWED_IP_RANGES: '127.0.0.0/8', EXECUTIONS_DATA_SAVE_ON_SUCCESS: 'all',
  };
  async function cli(...args) {
    const child = spawn('n8n', args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let log = '';
    child.stdout.on('data', chunk => { log += chunk; });
    child.stderr.on('data', chunk => { log += chunk; });
    const watchdog = setTimeout(() => child.kill('SIGKILL'), 60_000);
    const code = await new Promise(resolve => child.once('exit', resolve));
    clearTimeout(watchdog);
    assert.equal(code, 0, `n8n ${args[0]} failed:\n${log}`);
  }
  async function until(predicate, description) {
    const deadline = Date.now() + 45_000;
    while (Date.now() < deadline) {
      if (failures.length) throw failures[0];
      if (runtime?.exitCode !== null && runtime?.exitCode !== undefined) throw new Error(`n8n exited:\n${runtimeLog}`);
      if (await predicate()) return;
      await delay(100);
    }
    throw new Error(`Timed out: ${description}\n${runtimeLog}`);
  }
  const credentials = { tatrmanAutomationApi: { id: 'local-automation', name: 'Local Automation' } };
  function node(name, parameters, trigger = false) {
    return { id: randomUUID(), name, type: `CUSTOM.${trigger ? 'tatrmanTrigger' : 'tatrmanRun'}`, typeVersion: 1,
      position: [0, 0], parameters, credentials, ...(trigger ? { webhookId: randomUUID() } : {}) };
  }
  const workflow = {
    id: 'tatrman-runtime-proof', name: 'Tatrman runtime proof', active: false, versionId: randomUUID(),
    settings: { executionOrder: 'v1' },
    nodes: [
      node('Trigger', { mode: 'webhook', deployment: 'release_native_event', kinds: ['run_closed'] }, true),
      node('Open', { operation: 'open', recipe: 'release_order', deployment: 'release_native_event', port: '={{ { keys: $json.payload.keys } }}', dryRun: true, idempotencyKey: "={{ $workflow.id + ':' + $json.runId }}" }),
      node('Evaluate', { operation: 'evaluate', runId: '={{ $json.runId }}' }),
      node('Routes', { operation: 'routes', runId: "={{ $('Open').item.json.runId }}", recipe: 'release_order', route: 'release' }),
      node('Perform', { operation: 'perform', runId: "={{ $('Open').item.json.runId }}", recipe: 'release_order', action: 'notify', keys: '={{ [$json.key] }}' }),
      node('Outcome', { operation: 'outcome', runId: "={{ $('Open').item.json.runId }}", recipe: 'release_order', action: 'notify', outcomes: "={{ [{ key: $('Routes').item.json.key, status: 'delivered', at: '2026-10-23T00:00:00Z' }] }}" }),
      node('Close', { operation: 'close', runId: "={{ $('Open').item.json.runId }}" }),
    ],
    connections: Object.fromEntries([['Trigger', 'Open'], ['Open', 'Evaluate'], ['Evaluate', 'Routes'], ['Routes', 'Perform'], ['Perform', 'Outcome'], ['Outcome', 'Close']].map(([source, target]) => [source, { main: [[{ node: target, type: 'main', index: 0 }]] }])),
  };
  const expandedWorkflow = JSON.parse(JSON.stringify(workflow));
  expandedWorkflow.id = 'tatrman-rows-proof';
  expandedWorkflow.name = 'Tatrman route rows proof';
  expandedWorkflow.versionId = randomUUID();
  expandedWorkflow.nodes = expandedWorkflow.nodes.filter(node => node.name !== 'Routes');
  for (const node of expandedWorkflow.nodes) {
    node.id = randomUUID();
    if (node.name === 'Trigger') {
      node.webhookId = randomUUID();
      Object.assign(node.parameters, { kinds: ['route_rows'], recipe: 'release_order', route: 'release' });
    }
    if (node.name === 'Open') {
      node.parameters.port = '={{ { key: $json.key } }}';
      node.parameters.idempotencyKey = "={{ $workflow.id + ':' + $json._tatrman.runId + ':' + $json.key }}";
    }
    if (node.name === 'Perform') node.parameters.keys = "={{ [$('Trigger').item.json.key] }}";
    if (node.name === 'Outcome') node.parameters.outcomes = "={{ [{ key: $('Trigger').item.json.key, status: 'delivered', at: '2026-10-23T00:00:00Z' }] }}";
  }
  delete expandedWorkflow.connections.Routes;
  expandedWorkflow.connections.Evaluate = { main: [[{ node: 'Perform', type: 'main', index: 0 }]] };
  try {
    await writeFile(join(directory, 'credentials.json'), JSON.stringify([{ id: 'local-automation', name: 'Local Automation', type: 'tatrmanAutomationApi', data: { baseUrl, token: 'local-workflow-token' } }]));
    await writeFile(join(directory, 'workflow.json'), JSON.stringify([workflow, expandedWorkflow]));
    await cli('import:credentials', `--input=${join(directory, 'credentials.json')}`);
    await cli('import:workflow', `--input=${join(directory, 'workflow.json')}`);
    await cli('publish:workflow', `--id=${workflow.id}`);
    await cli('publish:workflow', `--id=${expandedWorkflow.id}`);
    runtime = spawn('n8n', ['start'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    runtime.stdout.on('data', chunk => { runtimeLog += chunk; });
    runtime.stderr.on('data', chunk => { runtimeLog += chunk; });
    await until(async () => {
      try { return hooks.size === 2 && (await fetch(`${n8nUrl}/healthz/readiness`)).ok; } catch { return false; }
    }, 'workflow activation');
    const setup = await fetch(`${n8nUrl}/rest/owner/setup`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'runtime@example.test', firstName: 'Runtime', lastName: 'Proof', password: 'LocalRuntimeProof123!' }) });
    assert.equal(setup.status, 200, await setup.text());
    const cookie = setup.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
    const hook = [...hooks.values()].find(hook => hook.url.includes(workflow.nodes[0].webhookId));
    assert.ok(hook, `Missing first workflow hook; got ${JSON.stringify([...hooks.values()].map(hook => hook.url))}`);
    const raw = '{ "kind":"run_closed", "deployment":"release_native_event", "runId":"source-run", "payload":{"keys":[4711,4712]} }';
    const deliver = signature => fetch(hook.url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-tatrman-signature': signature }, body: raw });
    const before = calls.length;
    assert.equal((await deliver('0'.repeat(64))).status, 401);
    await delay(200);
    assert.equal(calls.length, before, 'Invalid signatures must not execute the workflow');
    const signature = createHmac('sha256', hook.secret).update(raw).digest('hex');
    const accepted = await deliver(`sha256=${signature}`);
    assert.equal(accepted.status, 200, await accepted.text());
    await until(() => calls.filter(call => call.path.endsWith('/close')).length === 2, 'two route items completing');
    assert.deepEqual(calls.filter(call => call.path.endsWith('/perform')).map(call => call.body.keys), [[4711], [4712]]);
    assert.deepEqual(calls.filter(call => call.path.endsWith('/outcome')).map(call => call.body.outcomes[0].key), [4711, 4712]);
    assert.deepEqual(calls.filter(call => call.path.endsWith('/routes/release')).map(call => call.query.offset), ['0', '1']);
    const secondHook = [...hooks.values()].find(hook => hook.url.includes(expandedWorkflow.nodes[0].webhookId));
    assert.ok(secondHook, 'Missing expanded workflow hook');
    assert.notEqual(hook.url, secondHook.url);
    for (const [id, callback] of [[workflow.id, hook], [expandedWorkflow.id, secondHook]]) {
      const response = await fetch(`${n8nUrl}/rest/workflows/${id}`, { headers: { cookie } });
      const saved = await response.json();
      assert.ok(saved.data.staticData?.['node:Trigger']?.secret === callback.secret, `Stored signing secret differs for ${id}; static data keys: ${Object.keys(saved.data.staticData ?? {})}`);
    }
    const secondSignature = createHmac('sha256', secondHook.secret).update(raw).digest('hex');
    const expanded = await fetch(secondHook.url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-tatrman-signature': secondSignature }, body: raw });
    assert.equal(expanded.status, 200, await expanded.text());
    await until(() => calls.filter(call => call.path.endsWith('/close')).length === 4, 'expanded trigger items completing');
    assert.deepEqual(calls.filter(call => call.path.endsWith('/perform')).map(call => call.body.keys), [[4711], [4712], [4711], [4712]]);
    // Exercise real runtime deactivation through the local owner's authenticated editor API.
    await until(async () => {
      const response = await fetch(`${n8nUrl}/rest/executions`, { headers: { cookie } });
      assert.equal(response.status, 200, await response.clone().text());
      const body = await response.json();
      const successes = body.data.results.filter(execution => execution.status === 'success');
      return [workflow.id, expandedWorkflow.id].every(id => successes.some(execution => execution.workflowId === id));
    }, 'persisted successful executions');
    for (const id of [workflow.id, expandedWorkflow.id]) {
      const deactivate = await fetch(`${n8nUrl}/rest/workflows/${id}/deactivate`, { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: '{}' });
      assert.equal(deactivate.status, 200, await deactivate.text());
    }
    await until(() => hooks.size === 0, 'remote webhook removal');
    assert.equal(calls.filter(call => call.method === 'DELETE').length, 2);
    console.log('PASS: two real n8n workflows, persisted credential/executions, HMAC HTTP dispatch, paged Routes + route_rows, fresh Open/Evaluate → Perform → Outcome → Close, paired expressions and deactivation');
  } catch (error) {
    console.error(runtimeLog);
    throw error;
  } finally {
    if (runtime && runtime.exitCode === null) {
      runtime.kill('SIGTERM');
      const watchdog = setTimeout(() => runtime.kill('SIGKILL'), 10_000);
      await new Promise(resolve => runtime.once('exit', resolve));
      clearTimeout(watchdog);
    }
    await new Promise(resolve => fake.close(resolve));
    await rm(directory, { recursive: true, force: true });
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
