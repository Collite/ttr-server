// SPDX-License-Identifier: Apache-2.0
import { DateTime } from 'luxon';
import { NodeConnectionTypes, NodeOperationError } from 'n8n-workflow';
import type { INodeExecutionData, INodeType, INodeTypeDescription, IPollFunctions } from 'n8n-workflow';
import { AutomationClient } from '../../src/client';
import { loadOptions } from '../../src/options';

const inFlightPolls = new Set<string>();
const overlap = 5 * 60_000;

export class TatrmanTrigger implements INodeType {
  description: INodeTypeDescription = {
    displayName: 'Tatrman Trigger', name: 'tatrmanTrigger', icon: { light: 'file:tatrman.svg', dark: 'file:tatrman.svg' }, group: ['trigger'], version: 1,
    subtitle: '={{$parameter["deployment"]}}',
    description: 'Poll for closed Tatrman automation runs', defaults: { name: 'Tatrman Trigger' },
    inputs: [], outputs: [NodeConnectionTypes.Main], credentials: [{ name: 'tatrmanAutomationApi', required: true }], polling: true,
    properties: [
      { displayName: 'Deployment Name or ID', name: 'deployment', type: 'options', typeOptions: { loadOptionsMethod: 'getDeployments' }, default: '', required: true, description: 'Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>' },
      { displayName: 'Since', name: 'since', type: 'dateTime', default: '', description: 'Optional run start-time lower bound for the first poll. Defaults to the first poll; values without an offset use the workflow timezone.' },
    ],
  };
  methods = { loadOptions };

  async poll(this: IPollFunctions): Promise<INodeExecutionData[][] | null> {
    const key = JSON.stringify([this.getWorkflow().id, this.getNode().id]);
    if (inFlightPolls.has(key)) {
      this.logger.debug('Skipping overlapping Tatrman poll');
      return null;
    }
    inFlightPolls.add(key);
    try {
      const state = this.getWorkflowStaticData('node');
      const deployment = String(this.getNodeParameter('deployment'));
      const configured = String(this.getNodeParameter('since', '') || '');
      const date = (value: string) => {
        const parsed = DateTime.fromISO(value, { zone: this.getTimezone() }).toMillis();
        if (!Number.isFinite(parsed)) throw new NodeOperationError(this.getNode(), 'Invalid polling timestamp');
        return parsed;
      };
      const fingerprint = JSON.stringify([deployment, configured ? new Date(date(configured)).toISOString() : '']);
      const same = state.pollFingerprint === fingerprint;
      const start = same ? date(String(state.pollStart)) : configured ? date(configured) : Date.now();
      let watermark = same ? date(String(state.pollSince)) : start;
      const since = new Date(Math.max(start, watermark - overlap)).toISOString();
      const seen = { ...((same ? state.pollSeen : {}) as Record<string, string>) };
      const output: INodeExecutionData[] = [];
      const client = new AutomationClient(this);
      const pageCursors = new Set<string>();
      let cursor: string | null = null;
      do {
        const page = await client.runs({ deployment, since, status: 'closed', ...(cursor ? { cursor } : {}) });
        for (const run of page.runs) {
          if (run.status !== 'closed') continue;
          if (!run.runId || !run.protocolRef) {
            throw new NodeOperationError(this.getNode(), 'Closed run has no ID or protocol reference');
          }
          const startedAt = date(run.startedAt);
          watermark = Math.max(watermark, startedAt);
          if (!Object.hasOwn(seen, run.runId)) {
            output.push({ json: {
              kind: 'run_closed', deployment, runId: run.runId, payload: run,
              _tatrman: { run: run.runId, protocolRef: run.protocolRef },
            } });
          }
          seen[run.runId] = new Date(startedAt).toISOString();
        }
        cursor = page.next;
        if (cursor) {
          if (pageCursors.has(cursor)) {
            throw new NodeOperationError(this.getNode(), 'Run list pagination made no progress');
          }
          pageCursors.add(cursor);
        }
      } while (cursor);
      for (const [id, startedAt] of Object.entries(seen)) {
        if (date(startedAt) < watermark - overlap) delete seen[id];
      }
      // Commit only after all pages succeed. n8n retains node static data across republish.
      state.pollFingerprint = fingerprint;
      state.pollStart = new Date(start).toISOString();
      state.pollSince = new Date(watermark).toISOString();
      state.pollSeen = seen;
      delete state.pollPending;
      return output.length ? [output] : null;
    } finally {
      inFlightPolls.delete(key);
    }
  }
}
