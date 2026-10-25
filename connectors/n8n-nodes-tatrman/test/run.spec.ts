import { describe, expect, it, vi } from 'vitest';
import { NodeApiError } from 'n8n-workflow';
import { TatrmanRun } from '../nodes/Tatrman/TatrmanRun.node';
import { AutomationClient } from '../src/client';
import { harness } from './harness';
const defaults = { recipe: 'release_order', deployment: 'release_native_event', runId: 'r/1', part: 'candidates', island: 'shortfall', route: 'release', action: 'notify', taskId: 't1', port: '{"key":4711}', keys: '[4711]', group: '', outcomes: '[{"key":4711,"status":"delivered","at":"2026-10-23T00:00:00Z"}]', answer: 'approve', note: 'ok', rows: '[]', asOf: '', bundle: '', dryRun: false, idempotencyKey: 'n8n-1', callbackUrl: '' };
const operations = [
  ['open', 'POST', '/recipes/release_order/runs', { deployment: defaults.deployment, port: { key: 4711 }, dryRun: false, idempotencyKey: 'n8n-1', origin: 'engine' }],
  ['part', 'POST', '/runs/r%2F1/parts/candidates', undefined],
  ['islands', 'POST', '/runs/r%2F1/islands/shortfall', undefined],
  ['evaluate', 'POST', '/runs/r%2F1/evaluate', undefined],
  ['routes', 'GET', '/runs/r%2F1/routes/release', undefined],
  ['perform', 'POST', '/runs/r%2F1/actions/notify/perform', { keys: [4711] }],
  ['outcome', 'POST', '/runs/r%2F1/actions/notify/outcome', { outcomes: JSON.parse(defaults.outcomes) }],
  ['digests', 'GET', '/runs/r%2F1/digests', undefined],
  ['close', 'POST', '/runs/r%2F1/close', undefined],
  ['answer', 'POST', '/tasks/t1/answer', { answer: 'approve', note: 'ok', rows: [] }],
  ['task', 'GET', '/tasks/t1', undefined],
] as const;
describe('Run wire contract', () => {
  it.each(operations)('%s sends its exact request', async (operation, method, path, body) => {
    const h = harness({ ...defaults, operation }, () => operation === 'routes' ? { rows: [{ key: 4711 }], rowCount: 1, truncated: false } : { runId: 'r1', replayed: false, status: 'open' });
    const result = await new TatrmanRun().execute.call(h.context);
    expect(h.request).toHaveBeenCalledWith('tatrmanAutomationApi', expect.objectContaining({ method, url: `https://fake.test/automation${path}`, ...(body ? { body } : {}) }));
    expect(result[0][0].json).toMatchObject(operation === 'routes' ? { key: 4711 } : { runId: 'r1', replayed: false });
    expect(result[0][0].pairedItem).toEqual({ item: 0 });
  });
  it('pages routes without losing row order', async () => {
    const h = harness({ ...defaults, operation: 'routes' }, options => options.qs?.offset === 0 ? { rows: [{ key: 1 }], rowCount: 2, truncated: true } : { rows: [{ key: 2 }], rowCount: 2, truncated: false });
    const result = await new TatrmanRun().execute.call(h.context);
    expect(result[0].map(x => x.json.key)).toEqual([1, 2]);
    expect(h.request).toHaveBeenCalledTimes(2);
  });
  it('preserves stale fingerprint code and differs in NodeApiError', async () => {
    const h = harness({ ...defaults, operation: 'open' }, () => ({ statusCode: 409, headers: {}, body: { error: { code: 'recipe-stale', message: 'Outdated', detail: { differs: ['text'] } } } }));
    const error = await new TatrmanRun().execute.call(h.context).catch(e => e);
    expect(error).toBeInstanceOf(NodeApiError);
    expect(error.message).toContain('recipe-stale');
    expect(error.description).toContain('text');
    expect(error.context).toMatchObject({ code: 'recipe-stale', detail: { differs: ['text'] } });
  });
  it('continueOnFail keeps item pairing and structured server error', async () => {
    const h = harness({ ...defaults, operation: 'close' }, () => ({ statusCode: 403, body: { error: { code: 'identity', message: 'Denied' } }, headers: {} }), 2);
    h.context.continueOnFail = () => true;
    const result = await new TatrmanRun().execute.call(h.context);
    expect(result[0]).toHaveLength(2);
    expect(result[0][1]).toMatchObject({ json: { code: 'identity' }, pairedItem: { item: 1 } });
  });
  it('rejects malformed JSON before making a request', async () => {
    const h = harness({ ...defaults, operation: 'open', port: 'nope' });
    await expect(new TatrmanRun().execute.call(h.context)).rejects.toThrow('Port');
    expect(h.request).not.toHaveBeenCalled();
  });
});
describe('bounded asynchronous requests', () => {
  it('follows same-origin Location and polls for at most 30 seconds', async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      const h = harness({}, () => ++calls === 1 ? { statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'running' } } : { status: 'evaluated' });
      const promise = new AutomationClient(h.context).evaluate('r1');
      await vi.runAllTimersAsync();
      await expect(promise).resolves.toEqual({ runId: 'r1', status: 'evaluated' });
      expect(h.request.mock.calls[1][1]).toMatchObject({ method: 'GET', url: 'https://fake.test/automation/runs/r1' });
    } finally { vi.useRealTimers(); }
  });
  it('never sends credentials to a foreign Location', async () => {
    const h = harness({}, () => ({ statusCode: 202, headers: { location: 'https://attacker.test/automation/runs/r1' }, body: { status: 'running' } }));
    await expect(new AutomationClient(h.context).evaluate('r1')).rejects.toThrow('Location');
    expect(h.request).toHaveBeenCalledTimes(1);
  });
});
it('returns async accepted envelopes without Location for windows and import', async () => {
  const h = harness({}, () => ({ statusCode: 202, headers: {}, body: { windowIds: ['w1'] } }));
  await expect(new AutomationClient(h.context).windowEvents('r', 'p', [])).resolves.toEqual({ windowIds: ['w1'] });
});
it('exhausts the async budget without retrying the mutation', async () => {
  vi.useFakeTimers();
  try {
    const h = harness({}, () => ({ statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { status: 'running' } }));
    const result = new AutomationClient(h.context).evaluate('r1').catch(error => error);
    await vi.runAllTimersAsync();
    expect((await result).message).toContain('polling budget');
    expect(h.request.mock.calls.filter(call => call[1].method === 'POST')).toHaveLength(1);
    expect(h.request.mock.calls.length).toBeLessThanOrEqual(7);
  } finally { vi.useRealTimers(); }
});
it('returns an accepted Open handle without waiting for evaluation', async () => {
  vi.useFakeTimers();
  try {
    let count = 0;
    const h = harness({}, () => ++count === 1 ? { statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'running' } } : { status: 'closed', protocolRef: 'p1' });
    const result = new AutomationClient(h.context).open('release_order', { deployment: 'release_native_event', port: { key: 4711 } });
    await vi.runAllTimersAsync();
    await expect(result).resolves.toMatchObject({ runId: 'r1', status: 'running', replayed: true });
  } finally { vi.useRealTimers(); }
});
it('omits optional selectors rather than turning omitted keys into an empty selection', async () => {
  const h = harness({ ...defaults, operation: 'perform', keys: '', group: '', callbackUrl: '' });
  await new TatrmanRun().execute.call(h.context);
  expect(h.request.mock.calls[0][1].body).toEqual({});
});
it('does not expose request credentials on transport failure', async () => {
  const h = harness({ ...defaults, operation: 'open' }, () => { throw new Error('Authorization: Bearer secret'); });
  const error = await new TatrmanRun().execute.call(h.context).catch(e => e);
  expect(JSON.stringify(error)).not.toContain('Bearer secret');
  expect(error.message).toContain('request failed');
});
it('sanitizes NodeApiError transport failures as well as plain errors', async () => {
  const h = harness({ ...defaults, operation: 'open' });
  h.request.mockRejectedValue(new NodeApiError(h.context.getNode(), { message: 'Bearer secret', request: { token: 'secret' } }));
  const error = await new TatrmanRun().execute.call(h.context).catch(e => e);
  expect(JSON.stringify(error)).not.toContain('Bearer secret');
  expect(error.message).toContain('request failed');
});
it.each(['20', new Date('2026-10-23T00:00:20Z').toUTCString()])('respects Retry-After %s and waits through open progress', async retryAfter => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-23T00:00:00Z'));
  try {
    const h = harness({}, () => ({ statusCode: 202, headers: { location: '/automation/runs/r1', 'retry-after': retryAfter }, body: { runId: 'r1', status: 'running' } }));
    const result = new AutomationClient(h.context).evaluate('r1').catch(error => error);
    await vi.advanceTimersByTimeAsync(19_999);
    expect(h.request).toHaveBeenCalledTimes(1);
    h.request.mockResolvedValueOnce({ statusCode: 200, headers: { 'retry-after': '15' }, body: { runId: 'r1', status: 'open' } });
    await vi.advanceTimersByTimeAsync(1);
    expect(h.request).toHaveBeenCalledTimes(2);
    expect((await result).message).toContain('polling budget');
    expect(h.request.mock.calls.filter(call => call[1].method === 'POST')).toHaveLength(1);
  } finally { vi.useRealTimers(); }
});
it('never returns open progress as a completed verdict when the deadline is exhausted', async () => {
  vi.useFakeTimers();
  try {
    const h = harness({});
    h.request.mockResolvedValueOnce({ statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'running' } });
    h.request.mockResolvedValueOnce({ statusCode: 200, headers: {}, body: { status: 'open' } });
    h.request.mockResolvedValueOnce({ statusCode: 200, headers: {}, body: { status: 'evaluated', verdict: { routes: {} } } });
    const result = new AutomationClient(h.context).evaluate('r1').catch(error => error);
    await vi.runAllTimersAsync();
    // Two 15-second waits leave no HTTP budget within 30 seconds: never return pending progress as success.
    expect((await result).message).toContain('polling budget');
  } finally { vi.useRealTimers(); }
});
