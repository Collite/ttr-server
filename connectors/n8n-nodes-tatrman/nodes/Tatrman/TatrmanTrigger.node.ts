// SPDX-License-Identifier: Apache-2.0
import { DateTime } from 'luxon';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { NodeConnectionTypes, NodeOperationError } from 'n8n-workflow';
import type { IHookFunctions, INodeExecutionData, INodeType, INodeTypeDescription, IPollFunctions, IWebhookFunctions, IWebhookResponseData } from 'n8n-workflow';
import { AutomationClient, rethrowDoorError } from '../../src/client';
import type { AutomationEvent } from '../../src/client';
import { loadOptions } from '../../src/options';
type EventContext = IWebhookFunctions | IPollFunctions;
const inFlightPolls = new Set<string>();
const requestedKinds = (context: IHookFunctions | EventContext) => context.getNodeParameter('kinds', []) as string[];
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
function isEvent(value: unknown): value is AutomationEvent {
  return record(value) && typeof value.kind === 'string' && typeof value.deployment === 'string' && value.deployment.length > 0 && record(value.payload)
    && ['signal', 'answer', 'window_closed', 'run_closed'].includes(value.kind)
    && (value.kind !== 'run_closed' || (typeof value.runId === 'string' && value.runId.length > 0));
}
function eventItems(context: EventContext, event: AutomationEvent): INodeExecutionData[] {
  if (event.deployment !== context.getNodeParameter('deployment')) return [];
  const kinds = requestedKinds(context);
  return kinds.includes(event.kind) ? [{ json: event }] : [];
}
export class TatrmanTrigger implements INodeType {
  description: INodeTypeDescription = {
    displayName: 'Tatrman Trigger', name: 'tatrmanTrigger', icon: { light: 'file:tatrman.svg', dark: 'file:tatrman.svg' }, group: ['trigger'], version: 1,
    subtitle: '={{$parameter["mode"]}}', description: 'Receive Tatrman automation events or poll completed runs', defaults: { name: 'Tatrman Trigger' },
    inputs: [], outputs: [NodeConnectionTypes.Main], credentials: [{ name: 'tatrmanAutomationApi', required: true }], polling: true,
    webhooks: [{ name: 'default', httpMethod: 'POST', path: 'tatrman', responseMode: 'onReceived' }],
    properties: [
      { displayName: 'Mode', name: 'mode', type: 'options', noDataExpression: true, default: 'webhook', options: [{ name: 'Webhook', value: 'webhook' }, { name: 'Poll', value: 'poll' }] },
      { displayName: 'Deployment Name or ID', name: 'deployment', type: 'options', typeOptions: { loadOptionsMethod: 'getDeployments' }, default: '', required: true, description: 'Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>' },
      { displayName: 'Events', name: 'kinds', type: 'multiOptions', default: ['run_closed'], required: true, options: [
        { name: 'Answer', value: 'answer' }, { name: 'Run Closed', value: 'run_closed' }, { name: 'Signal', value: 'signal' }, { name: 'Window Closed', value: 'window_closed' },
      ], description: 'Events to receive. Poll mode supports Run Closed only.' },
      { displayName: 'Since', name: 'since', type: 'dateTime', default: '', displayOptions: { show: { mode: ['poll'] } }, description: 'Optional creation-time lower bound for the first poll. Defaults to the first poll; values without an offset use the workflow timezone.' },
    ],
  };
  methods = { loadOptions };
  webhookMethods = { default: {
    async checkExists(this: IHookFunctions) {
      if (this.getNodeParameter('mode') === 'poll') return true;
      const state = this.getWorkflowStaticData('node');
      if (!state.webhookId || !state.secret) return false;
      return (await new AutomationClient(this).webhooks()).webhooks.some(hook => hook.id === state.webhookId);
    },
    async create(this: IHookFunctions) {
      const state = this.getWorkflowStaticData('node');
      if (this.getNodeParameter('mode') === 'poll') return true;
      for (const key of Object.keys(state)) if (key.startsWith('poll')) delete state[key];
      const url = this.getNodeWebhookUrl('default');
      if (!url) throw new NodeOperationError(this.getNode(), 'No n8n webhook URL is available');
      state.secret = randomBytes(32).toString('hex');
      const hook = await new AutomationClient(this).createWebhook({ deployment: String(this.getNodeParameter('deployment')), url, secret: state.secret, kinds: [...new Set(requestedKinds(this))] });
      state.webhookId = hook.id;
      return true;
    },
    async delete(this: IHookFunctions) {
      const state = this.getWorkflowStaticData('node');
      if (state.webhookId) {
        try { await new AutomationClient(this).deleteWebhook(String(state.webhookId)); }
        catch (error) { if ((error as { httpCode?: string }).httpCode !== '404') rethrowDoorError(this.getNode(), error); }
      }
      for (const key of ['webhookId', 'secret']) delete state[key];
      return true;
    },
  } };
  async webhook(this: IWebhookFunctions): Promise<IWebhookResponseData> {
    const state = this.getWorkflowStaticData('node');
    const request = this.getRequestObject();
    const rawBody: unknown = request.rawBody ?? request.body;
    const signature = this.getHeaderData()['x-tatrman-signature'];
    const hex = typeof signature === 'string' ? signature.replace(/^sha256=/, '') : '';
    const validBytes = Buffer.isBuffer(rawBody);
    const expected = createHmac('sha256', String(state.secret ?? '')).update(validBytes ? rawBody : '').digest();
    if (this.getNodeParameter('mode') !== 'webhook' || !state.secret || !validBytes || !/^[a-fA-F0-9]{64}$/.test(hex) || !timingSafeEqual(Buffer.from(hex, 'hex'), expected)) {
      this.getResponseObject().status(401).json({ error: 'Invalid Tatrman signature' });
      return { noWebhookResponse: true };
    }
    let event: AutomationEvent;
    try {
      const parsed: unknown = JSON.parse(rawBody.toString('utf8'));
      if (!isEvent(parsed)) throw new NodeOperationError(this.getNode(), 'Invalid event envelope');
      event = parsed;
    }
    catch {
      this.getResponseObject().status(400).json({ error: 'Invalid event envelope' });
      return { noWebhookResponse: true };
    }
    const items = eventItems(this, event);
    return items.length ? { workflowData: [items] } : {};
  }
  async poll(this: IPollFunctions): Promise<INodeExecutionData[][] | null> {
    if (this.getNodeParameter('mode') !== 'poll') return null;
    const key = JSON.stringify([this.getWorkflow().id, this.getNode().id]);
    if (inFlightPolls.has(key)) { this.logger.debug('Skipping overlapping Tatrman poll'); return null; }
    inFlightPolls.add(key);
    try {
      if (requestedKinds(this).some(kind => kind !== 'run_closed')) {
        throw new NodeOperationError(this.getNode(), 'Poll mode supports Run Closed; other events require a webhook');
      }
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
      let cursor = same ? date(String(state.pollSince)) : start;
      const overlap = 5 * 60_000; // Creation can precede commit: re-read a bounded overlap and deduplicate.
      const since = new Date(Math.max(start, cursor - overlap)).toISOString();
      const seen = { ...((same ? state.pollSeen : {}) as Record<string, string>) };
      const pending = { ...((same ? state.pollPending : {}) as Record<string, string>) };
      const client = new AutomationClient(this);
      const response = await client.runs({ deployment, since });
      if (response.truncated) throw new NodeOperationError(this.getNode(), 'Run list is truncated; narrow the Since date or use webhook mode');
      const listed = new Map(response.runs.map(run => [String(run.runId ?? run.id), run]));
      for (const [id, createdAt] of Object.entries(pending)) {
        if (listed.has(id)) continue;
        try { listed.set(id, { ...(await client.run(id)), runId: id, createdAt }); }
        catch (error) {
          if (['403', '404'].includes(String((error as { httpCode?: string }).httpCode))) {
            delete pending[id];
            this.logger.warn(`Dropping unreadable polled run ${id}`);
          } else rethrowDoorError(this.getNode(), error);
        }
      }
      const output: INodeExecutionData[] = [];
      for (const [id, run] of listed) {
        if (!id || id === 'undefined') throw new NodeOperationError(this.getNode(), 'Run list entry has no ID');
        const createdAt = new Date(date(String(run.createdAt))).toISOString();
        cursor = Math.max(cursor, date(createdAt));
        if (run.status === 'closed') {
          if (!seen[id]) output.push(...eventItems(this, { kind: 'run_closed', deployment, runId: id, payload: run }));
          seen[id] = createdAt;
          delete pending[id];
        } else if (['failed', 'abandoned'].includes(String(run.status))) {
          delete pending[id];
          this.logger.warn(`Dropping terminal polled run ${id}: ${String(run.status)}`);
        } else pending[id] = createdAt;
      }
      for (const [id, createdAt] of Object.entries(seen)) if (date(createdAt) < cursor - overlap) delete seen[id];
      // Commit only after all reads succeed. Deactivation never owns or resets this state.
      state.pollFingerprint = fingerprint;
      state.pollStart = new Date(start).toISOString();
      state.pollSince = new Date(cursor).toISOString();
      state.pollSeen = seen;
      state.pollPending = pending;
      return output.length ? [output] : null;
    } finally { inFlightPolls.delete(key); }
  }
}
