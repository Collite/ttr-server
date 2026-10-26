// Run in the pinned n8n or derived image with --network none. Uses real n8n persistence and execution;
// only the Automation service is fake. All data and credentials are temporary.
const assert = require('node:assert/strict');
const { spawn, execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { createHmac, randomUUID } = require('node:crypto');
const { mkdtemp, mkdir, writeFile, rm } = require('node:fs/promises');
const http = require('node:http');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

async function main() {
  const directory = await mkdtemp(join(tmpdir(), 'tatrman-workflow-'));
  const runtimeCommand = process.env.N8N_TEST_PACKAGE_ARCHIVE ? 'n8n' : '/opt/tatrman-entrypoint.sh';
  if (process.env.N8N_TEST_PACKAGE_ARCHIVE) {
    const installation = join(directory, '.n8n', 'nodes');
    await mkdir(installation, { recursive: true });
    await writeFile(join(installation, 'package.json'), JSON.stringify({ private: true }));
    await promisify(execFile)('npm', ['install', '--prefix', installation, '--offline', '--ignore-scripts', '--legacy-peer-deps', '--no-audit', '--no-fund', process.env.N8N_TEST_PACKAGE_ARCHIVE]);
  }
  const hooks = new Map();
  const calls = [];
  const failures = [];
  const runs = new Map([['source-run', { status: 'closed', keys: [4711, 4712] }]]);
  let freshRuns = 0;
  let pollPhase = 0;
  const keys = Array.from({ length: 450 }, (_, i) => i + 1);
  const createdAt = '2026-10-23T01:00:00.000Z';
  let runtime;
  let runtimeLog = '';
  let env;
  const fake = http.createServer(async (req, res) => {
    try {
      if (req.headers.authorization !== 'Bearer local-workflow-token') { res.writeHead(401); res.end(); return; }
      let raw = '';
      for await (const chunk of req) raw += chunk;
      const body = raw ? JSON.parse(raw) : undefined;
      const url = new URL(req.url, 'http://fake');
      calls.push({ method: req.method, path: url.pathname, body, query: Object.fromEntries(url.searchParams) });
      const runId = url.pathname.split('/')[3];
      const run = runs.get(runId);
      let result;
      if (url.pathname === '/automation/') result = { deployments: [], recipes: [], programs: [] };
      else if (url.pathname === '/automation/runs') result = { runs: [{ runId: 'poll-closed', status: 'closed', createdAt }, ...(pollPhase ? [] : [{ runId: 'poll-pending', status: 'open', createdAt }])] };
      else if (url.pathname === '/automation/runs/poll-pending') result = { runId: 'poll-pending', status: pollPhase ? 'closed' : 'open', createdAt };
      else if (url.pathname.startsWith('/automation/tasks/polled-')) result = { id: url.pathname.split('/').pop() };
      else if (url.pathname === '/automation/webhooks' && req.method === 'GET') result = { webhooks: [...hooks.keys()].map(id => ({ id })) };
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
        if (run.status === 'closed') { res.writeHead(503, { 'content-type': 'application/json' }); res.end(JSON.stringify({ error: { code: 'run-abandoned' } })); return; }
        assert.equal(run.status, 'evaluated');
        const limit = Number(url.searchParams.get('limit') ?? 200);
        if (!(limit >= 1 && limit <= 200)) { res.writeHead(400); res.end(); return; }
        assert.equal(url.searchParams.has('limit'), false, 'Use server default page size');
        const offset = Number(url.searchParams.get('offset'));
        assert.ok(offset >= 0 && offset < run.keys.length);
        result = { rows: run.keys.slice(offset, offset + limit).map(key => ({ key, verdict_reason: 'fixture' })), rowCount: run.keys.length, // RouteRows.kt: rowCount > rows.size, even on the final page.
          truncated: run.keys.length > run.keys.slice(offset, offset + limit).length };
      } else if (url.pathname.endsWith('/actions/notify/perform') && run) {
        assert.equal(req.method, 'POST');
        assert.equal(run.status, 'evaluated', 'Never perform new work on the sealed source run');
        assert.ok(body.keys.length === 1 && run.keys.includes(body.keys[0]));
        result = { performed: [{ key: body.keys[0], batchRows: 1 }], skipped: [] };
      } else if (url.pathname.endsWith('/actions/notify/outcome') && run) {
        assert.equal(req.method, 'POST');
        assert.equal(run.status, 'evaluated');
        assert.deepEqual(body.outcomes, [{ key: body.outcomes[0].key, status: 'delivered', at: '2026-10-23T00:00:00Z' }]);
        assert.ok(keys.includes(body.outcomes[0].key));
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
    N8N_LISTEN_ADDRESS: '127.0.0.1', N8N_PORT: '5678',
    N8N_WEBHOOK_URL: `${n8nUrl}/`, N8N_SECURE_COOKIE: 'false', N8N_DIAGNOSTICS_ENABLED: 'false',
    N8N_VERSION_NOTIFICATIONS_ENABLED: 'false', N8N_TEMPLATES_ENABLED: 'false', N8N_RUNNERS_MODE: 'external',
    N8N_RUNNERS_AUTH_TOKEN: 'ephemeral-runner-token-no-code-nodes-used',
    N8N_SSRF_ALLOWED_IP_RANGES: '127.0.0.0/8', EXECUTIONS_DATA_SAVE_ON_SUCCESS: 'all',
  };
  async function cli(...args) {
    const child = spawn(runtimeCommand, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let log = '';
    child.stdout.on('data', chunk => { log += chunk; });
    child.stderr.on('data', chunk => { log += chunk; });
    const watchdog = setTimeout(() => child.kill('SIGKILL'), 60_000);
    const code = await new Promise(resolve => child.once('exit', resolve));
    clearTimeout(watchdog);
    assert.equal(code, 0, `n8n ${args[0]} failed:\n${log}`);
  }
  async function until(predicate, description, timeout = 45_000) {
    const deadline = Date.now() + timeout;
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
    return { id: randomUUID(), name, type: `@collite/n8n-nodes-tatrman.${trigger ? 'tatrmanTrigger' : 'tatrmanRun'}`, typeVersion: 1,
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
  // Exercise n8n-core's real expression resolver: missing selectors/keys must never mutate.
  workflow.nodes.push({ ...node('Missing selection', { operation: 'perform', runId: 'source-run', action: 'notify', keys: '={{ $json.payload.selected }}' }), continueOnFail: true });
  workflow.nodes.push({ ...node('Missing idempotency', { operation: 'open', recipe: 'release_order', deployment: 'release_native_event', port: '{}', dryRun: true, idempotencyKey: '={{ $json.eventId }}' }), continueOnFail: true });
  workflow.connections.Trigger.main[0].push({ node: 'Missing selection', type: 'main', index: 0 }, { node: 'Missing idempotency', type: 'main', index: 0 });
  const pollWorkflow = {
    id: 'tatrman-poll-proof', name: 'Tatrman polling proof', active: false, versionId: randomUUID(),
    settings: { executionOrder: 'v1' },
    nodes: [node('Trigger', { mode: 'poll', deployment: 'release_native_event', kinds: ['run_closed'], since: '2026-10-23T00:00:00Z', pollTimes: { item: [{ mode: 'custom', cronExpression: '* * * * *' }] } }, true),
      node('Task', { operation: 'task', taskId: '={{ "polled-" + $json.runId }}' })],
    connections: { Trigger: { main: [[{ node: 'Task', type: 'main', index: 0 }]] } },
  };
  try {
    await writeFile(join(directory, 'credentials.json'), JSON.stringify([{ id: 'local-automation', name: 'Local Automation', type: 'tatrmanAutomationApi', data: { baseUrl, token: 'local-workflow-token' } }]));
    await writeFile(join(directory, 'workflow.json'), JSON.stringify([workflow, pollWorkflow]));
    await cli('import:credentials', `--input=${join(directory, 'credentials.json')}`);
    await cli('import:workflow', `--input=${join(directory, 'workflow.json')}`);
    await cli('publish:workflow', `--id=${workflow.id}`);
    await cli('publish:workflow', `--id=${pollWorkflow.id}`);
    runtime = spawn(runtimeCommand, ['start'], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    runtime.stdout.on('data', chunk => { runtimeLog += chunk; });
    runtime.stderr.on('data', chunk => { runtimeLog += chunk; });
    await until(async () => {
      try { return hooks.size === 1 && (await fetch(`${n8nUrl}/healthz/readiness`)).ok; } catch { return false; }
    }, 'workflow activation');
    const setup = await fetch(`${n8nUrl}/rest/owner/setup`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'runtime@example.test', firstName: 'Runtime', lastName: 'Proof', password: 'LocalRuntimeProof123!' }) });
    assert.equal(setup.status, 200, await setup.text());
    const cookie = setup.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
    const names = ['@collite/n8n-nodes-tatrman.tatrmanRun', '@collite/n8n-nodes-tatrman.tatrmanTrigger'];
    const types = await fetch(`${n8nUrl}/rest/node-types`, {
      method: 'POST', headers: { cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ nodeInfos: names.map(name => ({ name, version: 1 })) }),
    });
    assert.equal(types.status, 200, await types.clone().text());
    assert.deepEqual((await types.json()).data.map(type => type.name).sort(), ['tatrmanRun', 'tatrmanTrigger']);
    async function api(path, method = 'GET', body) {
      const response = await fetch(`${n8nUrl}/rest/${path}`, { method, headers: { cookie, 'content-type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
      assert.equal(response.status, 200, await response.clone().text());
      return (await response.json()).data;
    }
    for (const [token, status] of [['wrong-token', 'Error'], ['local-workflow-token', 'OK']]) {
      const tested = await api('credentials/test', 'POST', { credentials: { id: '', name: 'Local test', type: 'tatrmanAutomationApi', data: { baseUrl, token } } });
      assert.equal(tested.status, status);
    }
    await until(() => calls.some(call => call.path === '/automation/tasks/polled-poll-closed'), 'first real poll execution', 70_000);
    const hook = [...hooks.values()].find(hook => hook.url.includes(workflow.nodes[0].webhookId));
    assert.ok(hook, `Missing first workflow hook; got ${JSON.stringify([...hooks.values()].map(hook => hook.url))}`);
    const raw = JSON.stringify({ kind: 'run_closed', deployment: 'release_native_event', runId: 'source-run', payload: { keys } });
    const deliver = signature => fetch(hook.url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-tatrman-signature': signature }, body: raw });
    const before = calls.length;
    assert.equal((await deliver('0'.repeat(64))).status, 401);
    await delay(200);
    assert.equal(calls.length, before, 'Invalid signatures must not execute the workflow');
    const signature = createHmac('sha256', hook.secret).update(raw).digest('hex');
    const accepted = await deliver(`sha256=${signature}`);
    assert.equal(accepted.status, 200, await accepted.text());
    await until(() => calls.filter(call => call.path.endsWith('/close')).length === keys.length, '450 route items completing');
    assert.deepEqual(calls.filter(call => call.path.endsWith('/perform')).map(call => call.body.keys), keys.map(key => [key]));
    assert.deepEqual(calls.filter(call => call.path.endsWith('/outcome')).map(call => call.body.outcomes[0].key), keys);
    assert.deepEqual(calls.filter(call => call.path.endsWith('/routes/release')).map(call => call.query.offset), ['0', '200', '400']);
    // Closed sessions are released by the real Server; the fake must enforce that too.
    const closed = await fetch(`${baseUrl}/automation/runs/source-run/routes/release`, { headers: { authorization: 'Bearer local-workflow-token' } });
    assert.equal(closed.status, 503);
    assert.equal((await closed.json()).error.code, 'run-abandoned');
    await delay(1500);
    const executionsBefore = (await api('executions')).results.length;
    const ignored = JSON.stringify({ kind: 'run_closed', deployment: 'other', runId: 'source-run', payload: {} });
    assert.equal((await fetch(hook.url, { method: 'POST', headers: { 'content-type': 'application/json', 'x-tatrman-signature': createHmac('sha256', hook.secret).update(ignored).digest('hex') }, body: ignored })).status, 200);
    await delay(1500);
    assert.equal((await api('executions')).results.length, executionsBefore, 'Filtered event must not create an execution');
    const saved = await api(`workflows/${pollWorkflow.id}`);
    assert.ok(saved.staticData?.['node:Trigger']?.pollPending?.['poll-pending'], 'Pending poll run must be persisted before edit');
    saved.nodes[0].notes = 'Edited while active for review-160';
    const edited = await api(`workflows/${pollWorkflow.id}`, 'PATCH', { name: 'Edited active poll', nodes: saved.nodes, connections: saved.connections, settings: saved.settings, versionId: saved.versionId });
    assert.notEqual(edited.versionId, saved.activeVersionId, 'The edit must create a new version to republish');
    await api(`workflows/${pollWorkflow.id}/activate`, 'POST', { versionId: edited.versionId });
    pollPhase = 1;
    await until(() => calls.some(call => call.path === '/automation/tasks/polled-poll-pending'), 'pending run completion after active edit/save/republish', 70_000);
    await delay(1500);
    assert.equal(calls.filter(call => call.path === '/automation/tasks/polled-poll-closed').length, 1, 'Republish must preserve deduplication');
    assert.equal(calls.filter(call => call.path === '/automation/tasks/polled-poll-pending').length, 1, 'Pending run is emitted once');
    // Exercise real runtime deactivation through the local owner's authenticated editor API.
    await until(async () => {
      const response = await fetch(`${n8nUrl}/rest/executions`, { headers: { cookie } });
      assert.equal(response.status, 200, await response.clone().text());
      const body = await response.json();
      const successes = body.data.results.filter(execution => execution.status === 'success');
      return [workflow.id, pollWorkflow.id].every(id => successes.some(execution => execution.workflowId === id));
    }, 'persisted successful executions');
    assert.equal(calls.filter(call => call.path === '/automation/recipes/release_order/runs').length, 1, 'Missing Idempotency Key expression must not send Open');
    assert.equal(calls.filter(call => call.path.includes('/runs/source-run/actions/')).length, 0, 'Missing Keys expression must not send Perform');
    for (const id of [workflow.id, pollWorkflow.id]) {
      const deactivate = await fetch(`${n8nUrl}/rest/workflows/${id}/deactivate`, { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: '{}' });
      assert.equal(deactivate.status, 200, await deactivate.text());
    }
    await until(() => hooks.size === 0, 'remote webhook removal');
    assert.equal(calls.filter(call => call.method === 'DELETE').length, 1);
    console.log('PASS: scoped package IDs, missing-expression mutation refusal, credential auth, 450-row server-default paging, closed-route refusal, filtered-event suppression, real poll state across active edit/save/republish, paired workflows and deactivation');
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
