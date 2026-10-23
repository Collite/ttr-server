import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { NodeApiError, NodeConnectionTypes, NodeOperationError } from 'n8n-workflow';
import type { IHookFunctions, INodeExecutionData, INodeType, INodeTypeDescription, IPollFunctions, IWebhookFunctions, IWebhookResponseData, JsonObject } from 'n8n-workflow';
import { AutomationClient } from '../../src/client';
import type { AutomationEvent } from '../../src/client';
import { loadOptions } from '../../src/options';
type EventContext = IWebhookFunctions | IPollFunctions;
const requestedKinds = (context: IHookFunctions | EventContext) => context.getNodeParameter('kinds', []) as string[];
const wireKinds = (kinds: string[]) => [...new Set(kinds.map(kind => kind === 'route_rows' ? 'run_closed' : kind))];
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
function isEvent(value: unknown): value is AutomationEvent {
  return record(value) && typeof value.kind === 'string' && typeof value.deployment === 'string' && value.deployment.length > 0 && record(value.payload)
    && ['signal', 'answer', 'window_closed', 'run_closed'].includes(value.kind)
    && (value.kind !== 'run_closed' || (typeof value.runId === 'string' && value.runId.length > 0));
}
async function eventItems(context: EventContext, event: AutomationEvent): Promise<INodeExecutionData[]> {
  if (event.deployment !== context.getNodeParameter('deployment')) return [];
  const kinds = requestedKinds(context);
  if (event.kind === 'run_closed' && kinds.includes('route_rows') && event.runId) {
    const rows = await new AutomationClient(context).routeRows(event.runId, String(context.getNodeParameter('route')));
    return rows.map(json => ({ json: { ...json, _tatrman: { runId: event.runId, deployment: event.deployment, route: context.getNodeParameter('route') as string } } }));
  }
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
        { name: 'Answer', value: 'answer' }, { name: 'Route Rows', value: 'route_rows' }, { name: 'Run Closed', value: 'run_closed' }, { name: 'Signal', value: 'signal' }, { name: 'Window Closed', value: 'window_closed' },
      ], description: 'Events to receive. Poll mode supports Run Closed and Route Rows only.' },
      { displayName: 'Recipe Name or ID', name: 'recipe', type: 'options', typeOptions: { loadOptionsMethod: 'getRecipes' }, default: '', required: true, displayOptions: { show: { kinds: ['route_rows'] } }, description: 'Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>' },
      { displayName: 'Route Name or ID', name: 'route', type: 'options', typeOptions: { loadOptionsMethod: 'getRoutes', loadOptionsDependsOn: ['recipe'] }, default: '', required: true, displayOptions: { show: { kinds: ['route_rows'] } }, description: 'Choose from the list, or specify an ID using an <a href="https://docs.n8n.io/code/expressions/">expression</a>' },
      { displayName: 'Since', name: 'since', type: 'dateTime', default: '', displayOptions: { show: { mode: ['poll'] } }, description: 'Optional creation-time lower bound for the first poll. Defaults to activation time.' },
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
      if (this.getNodeParameter('mode') === 'poll') {
        state.pollSince = String(this.getNodeParameter('since', '') || new Date().toISOString());
        return true;
      }
      const url = this.getNodeWebhookUrl('default');
      if (!url) throw new NodeOperationError(this.getNode(), 'No n8n webhook URL is available');
      state.secret = randomBytes(32).toString('hex');
      const hook = await new AutomationClient(this).createWebhook({ deployment: String(this.getNodeParameter('deployment')), url, secret: state.secret, kinds: wireKinds(requestedKinds(this)) });
      state.webhookId = hook.id;
      return true;
    },
    async delete(this: IHookFunctions) {
      const state = this.getWorkflowStaticData('node');
      if (state.webhookId) {
        try { await new AutomationClient(this).deleteWebhook(String(state.webhookId)); }
        catch (error) { if ((error as { httpCode?: string }).httpCode !== '404') throw new NodeApiError(this.getNode(), error as JsonObject); }
      }
      for (const key of ['webhookId', 'secret', 'pollSince', 'pollSeen', 'pollPending']) delete state[key];
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
    return { workflowData: [await eventItems(this, event)] };
  }
  async poll(this: IPollFunctions): Promise<INodeExecutionData[][] | null> {
    if (this.getNodeParameter('mode') !== 'poll') return null;
    if (requestedKinds(this).some(kind => !['run_closed', 'route_rows'].includes(kind))) {
      throw new NodeOperationError(this.getNode(), 'Poll mode supports Run Closed and Route Rows; other events require a webhook');
    }
    const state = this.getWorkflowStaticData('node');
    const since = String(state.pollSince ?? (this.getNodeParameter('since', '') || new Date().toISOString()));
    const client = new AutomationClient(this);
    const deployment = String(this.getNodeParameter('deployment'));
    const response = await client.runs({ deployment, since });
    if (response.truncated) throw new NodeOperationError(this.getNode(), 'Run list is truncated; narrow the Since date or use webhook mode');
    const seen = new Set((state.pollSeen ?? []) as string[]);
    const pending = new Set((state.pollPending ?? []) as string[]);
    const listed = new Map(response.runs.map(run => [String(run.runId ?? run.id), run]));
    // Runs opened on previous ticks may close after the creation-time cursor has advanced.
    for (const id of pending) if (!listed.has(id)) listed.set(id, { ...(await client.run(id)), runId: id });
    const output: INodeExecutionData[] = [];
    let cursor = since;
    for (const [id, run] of listed) {
      if (id === 'undefined') throw new NodeOperationError(this.getNode(), 'Run list entry has no ID');
      if (String(run.createdAt ?? '') > cursor) cursor = String(run.createdAt);
      if (run.status === 'closed' && !seen.has(id)) {
        output.push(...await eventItems(this, { kind: 'run_closed', deployment, runId: id, payload: run }));
        seen.add(id); pending.delete(id);
      } else if (['failed', 'abandoned'].includes(String(run.status))) pending.delete(id);
      else if (run.status !== 'closed') pending.add(id);
    }
    if (seen.size + pending.size > 10_000) throw new NodeOperationError(this.getNode(), 'Polling state exceeded 10000 runs; use webhook mode for this deployment');
    // Commit only after every fetch/expansion succeeds, so failures can be retried.
    state.pollSince = cursor;
    state.pollSeen = [...seen]; state.pollPending = [...pending];
    return output.length ? [output] : null;
  }
}
