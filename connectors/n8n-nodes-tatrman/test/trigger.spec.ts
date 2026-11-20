// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { IPollFunctions } from 'n8n-workflow';
import { TatrmanTrigger } from '../nodes/Tatrman/TatrmanTrigger.node';
import { harness } from './harness';

const trigger = new TatrmanTrigger();
const deployment = 'release_morning';
const since = '2026-11-20T07:00:00.000Z';

function pollFixture(respond: Parameters<typeof harness>[1]) {
  const parameters: Record<string, string> = { deployment, since };
  const h = harness(parameters, respond);
  const context = Object.assign(h.context, {
    getNodeParameter: (key: string, fallback?: unknown) => parameters[key] ?? fallback,
  }) as unknown as IPollFunctions;
  return { ...h, context };
}

function closedRun(runId: string, startedAt: string) {
  return { runId, deployment, status: 'closed', startedAt, protocolRef: `/automation/runs/${runId}/protocol` };
}

describe('Tatrman Trigger polling', () => {
  it('lists every page of closed runs by deployment and since, emitting one item per run', async () => {
    const first = closedRun('r1', '2026-11-20T07:01:00.000Z');
    const second = closedRun('r2', '2026-11-20T07:02:00.000Z');
    const h = pollFixture(options => options.qs?.cursor
      ? { runs: [first], next: null }
      : { runs: [second], next: 'page-2' });

    const items = (await trigger.poll.call(h.context))?.[0];

    expect(h.request).toHaveBeenNthCalledWith(1, 'tatrmanAutomationApi', expect.objectContaining({
      method: 'GET',
      url: 'https://fake.test/automation/runs',
      qs: { deployment, since, status: 'closed' },
    }));
    expect(h.request).toHaveBeenNthCalledWith(2, 'tatrmanAutomationApi', expect.objectContaining({
      qs: { deployment, since, status: 'closed', cursor: 'page-2' },
    }));
    expect(items).toHaveLength(2);
    expect(items?.map(item => item.json._tatrman)).toEqual(expect.arrayContaining([
      { run: first.runId, protocolRef: first.protocolRef },
      { run: second.runId, protocolRef: second.protocolRef },
    ]));
  });

  it('advances the saved cursor and suppresses duplicates across polls', async () => {
    const first = closedRun('r1', '2026-11-20T07:10:00.000Z');
    const second = closedRun('r2', '2026-11-20T07:11:00.000Z');
    let poll = 0;
    const h = pollFixture(() => ({ runs: ++poll === 1 ? [first] : [second, first], next: null }));

    expect((await trigger.poll.call(h.context))?.[0]).toHaveLength(1);
    expect(h.state.pollSince).toBe(first.startedAt);
    expect((await trigger.poll.call(h.context))?.[0].map(item => item.json._tatrman)).toEqual([
      { run: second.runId, protocolRef: second.protocolRef },
    ]);
    expect(h.state.pollSince).toBe(second.startedAt);
    expect(Date.parse(String(h.request.mock.calls[1][1].qs?.since))).toBeGreaterThan(Date.parse(since));
    expect(await trigger.poll.call(h.context)).toBeNull();
  });

  it('emits nothing when the list is empty', async () => {
    const h = pollFixture(() => ({ runs: [], next: null }));

    expect(await trigger.poll.call(h.context)).toBeNull();
    expect(h.request).toHaveBeenCalledTimes(1);
  });

  it('declares a poll-only trigger', () => {
    expect(trigger.description.polling).toBe(true);
    expect(trigger.description.webhooks).toBeUndefined();
    expect(trigger.description.properties.map(property => property.name)).toEqual(['deployment', 'since']);
  });
});
