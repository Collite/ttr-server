import { afterEach, expect, it, vi } from 'vitest';
import { Workflow } from 'n8n-workflow';
import type { IDataObject, IExecuteFunctions, IHookFunctions, INode, INodeTypes, IPollFunctions } from 'n8n-workflow';
import { TatrmanRun } from '../nodes/Tatrman/TatrmanRun.node';
import { TatrmanTrigger } from '../nodes/Tatrman/TatrmanTrigger.node';
import { harness, node } from './harness';
const run = new TatrmanRun();
const trigger = new TatrmanTrigger();
afterEach(() => vi.useRealTimers());
function expressions(parameters: IDataObject, items: IDataObject[]) {
  const activeNode: INode = { ...node, parameters: parameters as INode['parameters'] };
  const inputs = items.map(json => ({ json }));
  const workflow = new Workflow({ id: 'expression-proof', nodes: [activeNode], connections: {}, active: false, nodeTypes: { getByNameAndVersion: () => run } as unknown as INodeTypes });
  const h = harness(parameters, undefined, items.length);
  h.context.getNode = () => activeNode;
  h.context.getInputData = () => inputs;
  h.context.getNodeParameter = ((name: string, index = 0, fallback?: unknown, options?: { rawExpressions?: boolean }) => {
    const raw = Object.hasOwn(parameters, name) ? parameters[name] : fallback;
    if (options?.rawExpressions || typeof raw !== 'string' || !raw.startsWith('=')) return raw;
    return workflow.expression.getParameterValue(raw, null, 0, index, node.name, inputs, 'manual', {});
  }) as IExecuteFunctions['getNodeParameter'];
  return h;
}
it.each([{}, { selected: null }, { selected: '' }])('R1 missing Keys expression on %j refuses without any mutation', async item => {
  const h = expressions({ operation: 'perform', keys: '={{ $json.selected }}' }, [item]);
  await expect(run.execute.call(h.context)).rejects.toThrow('Keys expression resolved to nothing');
  expect(h.request).not.toHaveBeenCalled();
});
it.each(['group', 'rows', 'idempotencyKey'])('R1 empty %s expression refuses the item', async field => {
  const h = expressions({ operation: field === 'group' ? 'perform' : field === 'rows' ? 'answer' : 'open', port: '{}', [field]: '={{ $json.missing }}' }, [{}]);
  await expect(run.execute.call(h.context)).rejects.toThrow('expression resolved to nothing');
  expect(h.request).not.toHaveBeenCalled();
});
it('R1 literal blank Keys stays unrestricted while a missing Note is omitted', async () => {
  const h = expressions({ operation: 'perform', keys: '' }, [{}]);
  await run.execute.call(h.context);
  expect(h.request.mock.calls[0][1].body).toEqual({});
  const answer = expressions({ operation: 'answer', answer: 'approve', note: '={{ $json.note }}' }, [{}]);
  await run.execute.call(answer.context);
  expect(answer.request.mock.calls[0][1].body).toEqual({ answer: 'approve' });
});
it('R1 two input events keep distinct evaluated idempotency keys', async () => {
  const h = expressions({ operation: 'open', port: '{}', idempotencyKey: '={{ $json.eventId }}', asOf: '={{ $json.absent }}', bundle: '={{ $json.absent }}' }, [{ eventId: 'one' }, { eventId: 'two' }]);
  await run.execute.call(h.context);
  expect(h.request.mock.calls.map(call => (call[1].body as IDataObject)?.idempotencyKey)).toEqual(['one', 'two']);
  expect(h.request.mock.calls[0][1].body).not.toHaveProperty('asOf');
  expect(h.request.mock.calls[0][1].body).not.toHaveProperty('bundle');
});
it('R2 default Wait Up To survives twenty running polls', async () => {
  vi.useFakeTimers();
  let calls = 0;
  const h = harness({ operation: 'evaluate', runId: 'r1' }, () => ++calls === 1 ? { statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1' } } : { status: calls <= 21 ? 'running' : 'evaluated' });
  const result = run.execute.call(h.context).catch(error => error);
  await vi.runAllTimersAsync();
  expect((await result)[0][0].json.status).toBe('evaluated');
  expect(h.request).toHaveBeenCalledTimes(22);
});
it('R2 explicit 30-second Wait Up To still expires with a handle', async () => {
  vi.useFakeTimers();
  const h = harness({ operation: 'evaluate', runId: 'r1', waitUpTo: 30 }, () => ({ statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'running' } }));
  const result = run.execute.call(h.context).catch(error => error);
  await vi.runAllTimersAsync();
  expect((await result).context).toMatchObject({ runId: 'r1', location: 'https://fake.test/automation/runs/r1' });
  expect(h.request).toHaveBeenCalledTimes(2);
});
it('R2 Request Timeout of 200 seconds permits a 150-second Evaluate', async () => {
  vi.useFakeTimers();
  const h = harness({ operation: 'evaluate', runId: 'r1', requestTimeout: 200 }, options => new Promise((resolve, reject) => {
    const response = setTimeout(() => { clearTimeout(timeout); resolve({ verdict: {} }); }, 150_000);
    const timeout = setTimeout(() => { clearTimeout(response); reject(new Error('timeout')); }, Number(options.timeout));
  }));
  const result = run.execute.call(h.context).catch(error => error);
  await vi.runAllTimersAsync();
  expect((await result)[0][0].json).toEqual({ verdict: {} });
});
it('R2 a Part timeout identifies the read and run', async () => {
  const h = harness({ operation: 'part', runId: 'r1', part: 'candidates' }, () => { throw new Error('timeout'); });
  const error = await run.execute.call(h.context).catch(error => error);
  expect(error.context.runId).toBe('r1');
  expect(error.description).toContain('candidates');
});
it('R3 Continue On Fail preserves recovery context', async () => {
  const h = harness({ operation: 'evaluate', runId: 'r1' }, () => { throw new Error('secret bearer must stay hidden'); });
  h.context.continueOnFail = () => true;
  const item = (await run.execute.call(h.context))[0][0];
  expect(item.json).toMatchObject({ runId: 'r1', location: 'https://fake.test/automation/runs/r1/evaluate', description: expect.stringContaining('continues') });
  expect(JSON.stringify(item)).not.toContain('secret bearer');
});
it.each([['evaluate', undefined, 'continues'], ['open', '', 'may already exist'], ['open', 'event-1', 'same key']])('R3 %s gateway failure retains recovery advice', async (operation, idempotencyKey, advice) => {
  const h = harness({ operation, runId: 'r1', port: '{}', idempotencyKey }, () => ({ statusCode: 504, headers: {}, body: '<html>Gateway timeout</html>' }));
  h.context.continueOnFail = () => true;
  const item = (await run.execute.call(h.context))[0][0];
  expect(item.json.description).toContain(advice);
  if (operation === 'evaluate') expect(item.json.runId).toBe('r1');
});
it.each([201, 202])('R4 Open %s preserves replay flag and normalizes Location', async statusCode => {
  const h = harness({ operation: 'open', port: '{}' }, () => ({ statusCode, headers: {}, body: { runId: 'r1', replayed: false, status: 'open', location: '/automation/runs/r1' } }));
  h.context.getCredentials = vi.fn().mockResolvedValue({ baseUrl: 'https://gw/prefix' });
  expect((await run.execute.call(h.context))[0][0].json).toMatchObject({ replayed: false, location: 'https://gw/prefix/automation/runs/r1' });
});
function pollFixture() {
  const parameters: Record<string, unknown> = { mode: 'poll', kinds: ['run_closed'], deployment: 'd', since: '2026-10-23T00:00:00' };
  const h = harness({});
  const context = Object.assign(h.context, { getNodeParameter: (name: string, fallback?: unknown) => parameters[name] ?? fallback, getWorkflow: () => ({ id: 'overlap-proof' }), getTimezone: () => 'Europe/Prague', logger: { debug: vi.fn(), warn: vi.fn() }, getNodeWebhookUrl: () => 'https://n8n.test/hook' }) as unknown as IPollFunctions;
  return { ...h, context, parameters };
}
it('R6 zone-less Since uses workflow timezone', async () => {
  const h = pollFixture();
  h.request.mockResolvedValue({ statusCode: 200, headers: {}, body: { runs: [] } });
  await trigger.poll.call(h.context);
  expect(h.request.mock.calls[0][1].qs?.since).toBe('2026-10-22T22:00:00.000Z');
});
it('R6 webhook activation clears earlier poll history', async () => {
  const h = pollFixture();
  Object.assign(h.state, { pollSince: 'old', pollSeen: { a: 'date' }, pollPending: {}, pollFingerprint: 'old' });
  h.parameters.mode = 'webhook';
  h.request.mockResolvedValue({ statusCode: 201, body: { id: 'hook' }, headers: {} });
  await trigger.webhookMethods.default.create.call(h.context as unknown as IHookFunctions);
  expect(Object.keys(h.state).some(key => key.startsWith('poll'))).toBe(false);
});
it('R6 overlapping ticks skip the second read and emit once', async () => {
  const h = pollFixture();
  let release!: (value: Awaited<ReturnType<typeof h.request>>) => void;
  h.request.mockImplementation(() => new Promise(resolve => { release = resolve; }));
  const first = trigger.poll.call(h.context);
  await vi.waitFor(() => expect(h.request).toHaveBeenCalledTimes(1));
  const second = await trigger.poll.call(h.context);
  release({ statusCode: 200, headers: {}, body: { runs: [{ runId: 'r', status: 'closed', createdAt: '2026-10-23T01:00:00Z' }] } });
  expect((await first)?.[0]).toHaveLength(1);
  expect(second).toBeNull();
  expect(h.request).toHaveBeenCalledTimes(1);
});

it('R2 exposes explicit budget options only for the supported operations', () => {
  const wait = run.description.properties.find(property => property.name === 'waitUpTo');
  const request = run.description.properties.find(property => property.name === 'requestTimeout');
  expect(wait).toMatchObject({ default: 600, displayOptions: { show: { operation: ['evaluate'] } } });
  expect(request).toMatchObject({ default: 90, displayOptions: { show: { operation: ['evaluate', 'part', 'islands', 'routes'] } } });
});

it('R1 an SDK-evaluated empty Keys array remains a no-op', async () => {
  const h = expressions({ operation: 'perform', keys: '={{ $json.selected }}' }, [{ selected: [] }]);
  expect((await run.execute.call(h.context))[0][0].json).toEqual({ performed: [], skipped: [] });
  expect(h.request).not.toHaveBeenCalled();
});

it('R1 validates a missing Group expression even when Keys is an explicit no-op', async () => {
  const h = expressions({ operation: 'perform', keys: '[]', group: '={{ $json.group }}' }, [{}]);
  await expect(run.execute.call(h.context)).rejects.toThrow('Group expression resolved to nothing');
  expect(h.request).not.toHaveBeenCalled();
});
