// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NodeApiError } from 'n8n-workflow';
import type { IPollFunctions } from 'n8n-workflow';
import { AutomationClient } from '../src/client';
import { TatrmanRun } from '../nodes/Tatrman/TatrmanRun.node';
import { TatrmanTrigger } from '../nodes/Tatrman/TatrmanTrigger.node';
import { harness } from './harness';
afterEach(() => vi.useRealTimers());

it('R1 pages 450 rows using the server default without a limit override', async () => {
  const h = harness({}, options => {
    const offset = Number(options.qs?.offset ?? 0);
    if (options.qs?.limit && Number(options.qs.limit) > 200) return { statusCode: 400, body: { error: { code: 'invalid-limit' } } };
    expect(options.qs).not.toHaveProperty('limit');
    return { rows: Array.from({ length: Math.min(200, 450 - offset) }, (_, i) => ({ key: i + offset })), rowCount: 450, // RouteRows.kt: rowCount > rows.size, including the final page.
      truncated: 450 > Math.min(200, 450 - offset) };
  });
  expect(await new AutomationClient(h.context).routeRows('r1', 'release')).toHaveLength(450);
  expect(h.request.mock.calls.map(call => call[1].qs?.offset)).toEqual([0, 200, 400]);
});
function timedContext(wait: number) {
  return harness({}, options => new Promise((resolve, reject) => {
    const response = setTimeout(() => { clearTimeout(timeout); resolve({ verdict: {} }); }, wait);
    const timeout = setTimeout(() => { clearTimeout(response); reject(new Error('timeout with secret')); }, Number(options.timeout));
  }));
}
it('R2 allows a 45-second synchronous evaluation before starting any polling budget', async () => {
  vi.useFakeTimers();
  const h = timedContext(45_000);
  const result = new AutomationClient(h.context).evaluate('slow').catch(error => error);
  await vi.runAllTimersAsync();
  expect(await result).toEqual({ verdict: {} });
  expect(h.request.mock.calls[0][1].timeout).toBe(90_000);
});
it('R2 a request timeout preserves the run handle and says evaluation continues', async () => {
  vi.useFakeTimers();
  const h = timedContext(120_000);
  const result = new AutomationClient(h.context).evaluate('slow').catch(error => error);
  await vi.runAllTimersAsync();
  const error = await result;
  expect(error.context).toMatchObject({ runId: 'slow' });
  expect(error.description).toContain('continues');
  expect(JSON.stringify(error)).not.toContain('secret');
});
it('R5 Open returns a live idempotent replay without polling', async () => {
  const h = harness({}, () => ({ statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'open', replayed: false } }));
  expect(await new AutomationClient(h.context).open('r', { deployment: 'd', port: {} })).toMatchObject({ runId: 'r1', status: 'open', location: 'https://fake.test/automation/runs/r1', replayed: false });
  expect(h.request).toHaveBeenCalledTimes(1);
});
it('R5 a polling-budget error carries runId and Location', async () => {
  vi.useFakeTimers();
  const h = harness({}, () => ({ statusCode: 202, headers: { location: '/automation/runs/r1' }, body: { runId: 'r1', status: 'running' } }));
  const result = new AutomationClient(h.context).evaluate('r1').catch(error => error);
  await vi.runAllTimersAsync();
  expect((await result).context).toMatchObject({ runId: 'r1', location: 'https://fake.test/automation/runs/r1' });
});
it('R9 rebases server Locations under a gateway prefix', async () => {
  vi.useFakeTimers();
  const h = harness({});
  h.context.getCredentials = vi.fn().mockResolvedValue({ baseUrl: 'https://gw/tatrman', token: 'secret' });
  h.request.mockResolvedValueOnce({ statusCode: 202, headers: { location: '/automation/runs/x' }, body: { runId: 'x' } });
  h.request.mockResolvedValueOnce({ statusCode: 200, headers: {}, body: { status: 'evaluated' } });
  const result = new AutomationClient(h.context).evaluate('x');
  await vi.runAllTimersAsync();
  await result;
  expect(h.request.mock.calls[1][1].url).toBe('https://gw/tatrman/automation/runs/x');
});
it.each(['/other/x', 'https://evil/automation/runs/x', '/automation/../outside'])('R9 refuses unsafe Location %s', async location => {
  const h = harness({}, () => ({ statusCode: 202, headers: { location }, body: { runId: 'x' } }));
  await expect(new AutomationClient(h.context).evaluate('x')).rejects.toThrow('Location');
  expect(h.request).toHaveBeenCalledTimes(1);
});
it.each([[], '[]'])('R4 empty Keys %j perform nothing and make no request', async keys => {
  const h = harness({ operation: 'perform', keys });
  expect((await new TatrmanRun().execute.call(h.context))[0][0].json).toEqual({ performed: [], skipped: [] });
  expect(h.request).not.toHaveBeenCalled();
});
it('R4 preserves an explicit empty Rows array', async () => {
  const h = harness({ operation: 'answer', taskId: 't', rows: [], answer: 'approve' });
  await new TatrmanRun().execute.call(h.context);
  expect(h.request.mock.calls[0][1].body).toMatchObject({ rows: [] });
});
it('R12 normalizes empty response bodies and primitive array rows', async () => {
  const h = harness({ operation: 'close' }, () => '');
  expect((await new TatrmanRun().execute.call(h.context))[0][0].json).toEqual({});
  h.request.mockResolvedValue({ statusCode: 200, headers: {}, body: [1, null, { ok: true }] });
  expect((await new TatrmanRun().execute.call(h.context))[0].map(item => item.json)).toEqual([{ value: 1 }, { value: null }, { ok: true }]);
});
it('L1 preserves the failing input index on an existing NodeApiError', async () => {
  let calls = 0;
  const h = harness({ operation: 'close' }, () => ++calls === 1 ? {} : { statusCode: 403, body: { error: { code: 'identity' } } }, 2);
  const error = await new TatrmanRun().execute.call(h.context).catch(error => error);
  expect(error).toBeInstanceOf(NodeApiError);
  expect(error.context.itemIndex).toBe(1);
});
const trigger = new TatrmanTrigger();
function pollFixture(respond: Parameters<typeof harness>[1], since = '2026-10-25T00:00:00Z') {
  const h = harness({}, respond);
  const params: Record<string, unknown> = { deployment: 'd', since };
  const context = Object.assign(h.context, { getNodeParameter: (key: string, fallback?: unknown) => params[key] ?? fallback, logger: { warn: vi.fn() } }) as unknown as IPollFunctions;
  return { ...h, params, context };
}
describe('R6/R7/R11 poll state', () => {
  it('processes 12000 runs without retaining their entire history', async () => {
    let tick = 0;
    const h = pollFixture(() => ({ runs: [{ runId: `r${++tick}`, status: 'closed', protocolRef: 'protocol', startedAt: new Date(Date.parse('2026-10-25T00:00:00Z') + tick * 60_000).toISOString() }] }));
    for (let i = 0; i < 12000; i++) await trigger.poll.call(h.context);
    expect(Object.keys(h.state.pollSeen as object).length).toBeLessThanOrEqual(6);
  });
  it('does not commit partially processed state when a later page fails', async () => {
    const h = pollFixture(options => options.qs?.cursor
      ? { statusCode: 503, body: { error: { code: 'unavailable' } } }
      : { runs: [{ runId: 'r1', status: 'closed', protocolRef: 'protocol', startedAt: '2026-10-25T00:01:00Z' }], next: 'page-2' });
    await expect(trigger.poll.call(h.context)).rejects.toThrow();
    expect(h.state).toEqual({});
    expect(h.request.mock.calls[1][1].qs?.cursor).toBe('page-2');
  });
  it('retains state across ticks and resets on deployment change', async () => {
    const h = pollFixture(() => ({ runs: [{ runId: 'r', status: 'closed', protocolRef: 'protocol', startedAt: '2026-10-25T00:01:00Z' }] }));
    expect((await trigger.poll.call(h.context))?.[0]).toHaveLength(1);
    expect(await trigger.poll.call(h.context)).toBeNull();
    h.params.deployment = 'other';
    expect((await trigger.poll.call(h.context))?.[0]).toHaveLength(1);
  });
  it('orders timestamps numerically and emits a late listed run exactly once', async () => {
    let tick = 0;
    const h = pollFixture(() => ({ runs: ++tick === 1 ? [{ runId: 'r2', status: 'closed', protocolRef: 'protocol', startedAt: '2026-10-25T01:00:00.500Z' }] : [{ runId: 'r1', status: 'closed', protocolRef: 'protocol', startedAt: '2026-10-25T02:00:00+01:00' }, { runId: 'r2', status: 'closed', protocolRef: 'protocol', startedAt: '2026-10-25T01:00:00.500Z' }] }));
    await trigger.poll.call(h.context);
    expect((await trigger.poll.call(h.context))?.[0].map(item => item.json.runId)).toEqual(['r1']);
    expect(h.state.pollSince).toBe('2026-10-25T01:00:00.500Z');
    expect(await trigger.poll.call(h.context)).toBeNull();
    expect(h.request.mock.calls[1][1].qs?.since).toBe('2026-10-25T00:55:00.500Z');
  });
});
