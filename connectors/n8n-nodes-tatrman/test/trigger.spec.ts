import { createHmac } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import type { IHookFunctions, IPollFunctions, IWebhookFunctions } from 'n8n-workflow';
import { TatrmanTrigger } from '../nodes/Tatrman/TatrmanTrigger.node';
import { harness } from './harness';
const trigger = new TatrmanTrigger();
const params = { mode: 'webhook', deployment: 'release_native_event', recipe: 'release_order', kinds: ['signal'], route: 'release' };
function hooks(respond?: Parameters<typeof harness>[1]) {
  const h = harness(params, respond);
  return { ...h, context: Object.assign(h.context, { getNodeParameter: (key: string, fallback?: unknown) => (params as Record<string, unknown>)[key] ?? fallback, getNodeWebhookUrl: () => 'https://n8n.test/webhook/test' }) as unknown as IHookFunctions };
}
describe('webhook lifecycle', () => {
  it('registers a generated signing secret and unregisters the saved ID', async () => {
    const h = hooks(() => ({ id: 'hook-1' }));
    await trigger.webhookMethods.default.create.call(h.context);
    expect(h.request).toHaveBeenCalledWith('tatrmanAutomationApi', expect.objectContaining({ method: 'POST', url: 'https://fake.test/automation/webhooks', body: { deployment: params.deployment, url: 'https://n8n.test/webhook/test', secret: expect.stringMatching(/^[a-f0-9]{64}$/), kinds: ['signal'] } }));
    await trigger.webhookMethods.default.delete.call(h.context);
    expect(h.request).toHaveBeenLastCalledWith('tatrmanAutomationApi', expect.objectContaining({ method: 'DELETE', url: 'https://fake.test/automation/webhooks/hook-1' }));
    expect(h.state.webhookId).toBeUndefined();
    expect(h.state.secret).toBeUndefined();
  });
  it('checks the remote registry rather than trusting stale local state', async () => {
    const h = hooks(() => ({ webhooks: [] }));
    h.state.webhookId = 'gone'; h.state.secret = 'secret';
    expect(await trigger.webhookMethods.default.checkExists.call(h.context)).toBe(false);
    expect(h.request).toHaveBeenCalledWith('tatrmanAutomationApi', expect.objectContaining({ url: 'https://fake.test/automation/webhooks' }));
  });
});
function incoming(body: string, signature?: string, kinds = ['signal']) {
  const event = JSON.parse(body);
  const h = harness({ ...params, kinds }, () => ({ rows: [{ key: 4711 }, { key: 4712 }], rowCount: 2 }));
  h.state.secret = 'test-secret';
  const response = { status: vi.fn().mockReturnThis(), json: vi.fn().mockReturnThis() };
  const context = Object.assign(h.context, {
    getNodeParameter: (key: string, fallback?: unknown) => ({ ...params, kinds } as Record<string, unknown>)[key] ?? fallback,
    getRequestObject: () => ({ body: Buffer.from(body), rawBody: Buffer.from(body) }),
    getBodyData: () => event, getHeaderData: () => ({ 'x-tatrman-signature': signature }), getResponseObject: () => response,
  }) as unknown as IWebhookFunctions;
  return { ...h, context, response };
}
const sign = (body: string) => createHmac('sha256', 'test-secret').update(body).digest('hex');
it.each(['null', '[]', '{}', '{"kind":"signal","deployment":"release_native_event"}', '{"kind":"run_closed","deployment":"release_native_event","payload":{}}'])('rejects signed malformed envelope %s with 400', async raw => {
  const h = incoming(raw, sign(raw));
  const result = await trigger.webhook.call(h.context);
  expect(h.response.status).toHaveBeenCalledWith(400);
  expect(result.workflowData).toBeUndefined();
  expect(h.request).not.toHaveBeenCalled();
});
describe('event signature and delivery', () => {
  const body = '{ "kind":"signal", "deployment":"release_native_event", "payload":{"x":1} }';
  it('authenticates the exact raw bytes before emitting the event', async () => {
    const h = incoming(body, sign(body));
    expect((await trigger.webhook.call(h.context)).workflowData?.[0][0].json).toEqual(JSON.parse(body));
  });
  it.each([undefined, 'bad', '0'.repeat(64), sign(JSON.stringify(JSON.parse(body)))])('rejects invalid or reserialized signatures', async signature => {
    const h = incoming(body, signature);
    const result = await trigger.webhook.call(h.context);
    expect(h.response.status).toHaveBeenCalledWith(401);
    expect(result.workflowData).toBeUndefined();
    expect(h.request).not.toHaveBeenCalled();
  });
  it('ignores an authenticated event for another deployment', async () => {
    const raw = JSON.stringify({ kind: 'signal', deployment: 'other', payload: {} });
    const h = incoming(raw, sign(raw));
    expect((await trigger.webhook.call(h.context)).workflowData).toBeUndefined();
  });
});
describe('polling fallback', () => {
  it('lists closed runs by deployment and since, then deduplicates', async () => {
    const h = harness({}, options => options.url.endsWith('/runs') ? { runs: [{ runId: 'r1', status: 'closed', createdAt: '2026-10-23T01:00:00Z', closedAt: '2026-10-23T02:00:00Z' }] } : { rows: [{ key: 4711 }], rowCount: 1 });
    const p = { mode: 'poll', kinds: ['run_closed'], route: 'release', deployment: params.deployment, since: '2026-10-23T00:00:00Z' };
    const context = Object.assign(h.context, { getNodeParameter: (key: string, fallback?: unknown) => (p as Record<string, unknown>)[key] ?? fallback }) as unknown as IPollFunctions;
    expect((await trigger.poll.call(context))?.[0][0].json.runId).toBe('r1');
    expect(h.request.mock.calls[0][1].qs).toMatchObject({ deployment: params.deployment, since: new Date(p.since).toISOString() });
    expect(await trigger.poll.call(context)).toBeNull();
  });
  it('does no polling work in webhook mode', async () => {
    const h = hooks();
    expect(await trigger.poll.call(h.context as unknown as IPollFunctions)).toBeNull();
    expect(h.request).not.toHaveBeenCalled();
  });
});
it('tracks an open run across creation-time cursor advancement until it closes', async () => {
  let tick = 0;
  const h = harness({}, options => {
    if (options.url.endsWith('/runs')) return ++tick === 1 ? { runs: [{ runId: 'r1', status: 'running', createdAt: '2026-10-23T01:00:00Z' }, { runId: 'r2', status: 'closed', createdAt: '2026-10-23T02:00:00Z' }] } : { runs: [] };
    return { status: 'closed', createdAt: '2026-10-23T01:00:00Z' };
  });
  const p = { mode: 'poll', kinds: ['run_closed'], deployment: params.deployment, since: '2026-10-23T00:00:00Z' };
  const context = Object.assign(h.context, { getNodeParameter: (key: string, fallback?: unknown) => (p as Record<string, unknown>)[key] ?? fallback }) as unknown as IPollFunctions;
  expect((await trigger.poll.call(context))?.[0][0].json.runId).toBe('r2');
  expect((await trigger.poll.call(context))?.[0][0].json.runId).toBe('r1');
  expect(await trigger.poll.call(context)).toBeNull();
});
it('has no Route Rows option or expansion properties in P0', () => {
  expect(JSON.stringify(trigger.description)).not.toContain('route_rows');
  expect(trigger.description.properties.map(property => property.name)).not.toContain('route');
});
