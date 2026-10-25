import { NodeApiError, NodeOperationError, sleep } from 'n8n-workflow';
import type { IDataObject, IExecuteFunctions, IHookFunctions, IHttpRequestOptions, ILoadOptionsFunctions, IPollFunctions, IWebhookFunctions, INode, JsonObject } from 'n8n-workflow';

type Context = IExecuteFunctions | ILoadOptionsFunctions | IHookFunctions | IWebhookFunctions | IPollFunctions;
export interface Page extends IDataObject { rows: IDataObject[]; rowCount: number; truncated?: boolean }
export interface OpenInput extends IDataObject { deployment: string; port: IDataObject; asOf?: string; bundle?: string; dryRun?: boolean; idempotencyKey?: string; origin?: string }
export interface OpenResult extends IDataObject { runId: string; replayed?: boolean; status?: string }
export interface Descriptor extends IDataObject { deployments: IDataObject[]; recipes: IDataObject[]; programs: IDataObject[] }
export interface AutomationEvent extends IDataObject { kind: string; deployment: string; runId?: string; taskId?: string; payload: IDataObject }
// Server budgets.syncDefault is 60 s; allow transport/headroom before a 202 starts polling.
const HTTP_TIMEOUT_MS = 90_000;
const POLLING_BUDGET_MS = 30_000;
const enc = encodeURIComponent;
function pollingDelay(headers: Record<string, unknown> | undefined): number {
  const raw = headers?.['retry-after'] ?? headers?.['Retry-After'];
  const seconds = typeof raw === 'string' || typeof raw === 'number' ? Number(raw) : NaN;
  const delay = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(String(raw)) - Date.now();
  return Math.max(15_000, Number.isFinite(delay) ? delay : 15_000);
}

/** Preserve typed n8n errors (including item context); wrap only untyped failures. */
export function rethrowDoorError(node: INode, error: unknown, itemIndex?: number): never {
  if (error instanceof NodeApiError || error instanceof NodeOperationError) {
    if (itemIndex !== undefined) error.context.itemIndex = itemIndex;
    throw error;
  }
  throw new NodeApiError(node, error as JsonObject, { itemIndex });
}

/** Client of the governed door; n8n owns authentication and credential storage. */
export class AutomationClient {
  constructor(private readonly context: Context) {}

  async request<T = IDataObject>(method: IHttpRequestOptions['method'], path: string, body?: IDataObject, qs?: IDataObject, awaitCompletion = false, flight: { runId?: string; opening?: boolean; idempotencyKey?: string } = {}): Promise<T> {
    const credentials = await this.context.getCredentials('tatrmanAutomationApi');
    const root = new URL(`${String(credentials.baseUrl).replace(/\/+$/, '')}/automation/`);
    let url = new URL(path.replace(/^\//, ''), root);
    let deadline = Infinity;
    let polling = false;
    let initial: IDataObject = {};
    for (let attempt = 0; attempt <= 6; attempt++) {
      let response;
      try {
        response = await this.context.helpers.httpRequestWithAuthentication.call(this.context, 'tatrmanAutomationApi', {
          method: polling ? 'GET' : method, url: url.toString(), json: true,
          ...(polling ? {} : { ...(body === undefined ? {} : { body }), ...(qs ? { qs } : {}) }),
          returnFullResponse: true, ignoreHttpStatusErrors: true, disableFollowRedirect: true,
          timeout: polling ? Math.max(1, Math.min(HTTP_TIMEOUT_MS, deadline - Date.now())) : HTTP_TIMEOUT_MS,
        });
      } catch {
        // Do not expose a helper error's request configuration, which may contain the bearer.
        const description = flight.runId
          ? `The evaluation for run ${flight.runId} continues on the server if accepted. Retry Evaluate with this run ID to join it.`
          : flight.opening ? 'The run may already exist. Always set an Idempotency Key; retry Open only with the same key to avoid another run.'
            : 'The automation door could not be reached';
        const error = new NodeApiError(this.context.getNode(), {}, { message: 'Tatrman request failed', description });
        error.context = { runId: flight.runId ?? initial.runId, location: url.toString() };
        throw error;
      }
      const status = Number(response.statusCode);
      const value = response.body as IDataObject;
      if (status >= 400) {
        const fault = value?.error as IDataObject | undefined;
        const code = String(fault?.code ?? status);
        const error = new NodeApiError(this.context.getNode(), (value ?? {}) as JsonObject, {
          message: `${code}: ${String(fault?.message ?? 'Tatrman request failed')}`,
          description: JSON.stringify(fault?.detail ?? value), httpCode: String(status),
        });
        error.context = { code, detail: fault?.detail };
        throw error;
      }
      if (status >= 300 && status < 400) {
        throw new NodeOperationError(this.context.getNode(), 'Unexpected redirect from the automation door');
      }
      if (status === 202 && flight.opening) {
        const location = response.headers?.location ?? response.headers?.Location ?? value?.location;
        return { ...value, replayed: true, ...(location ? { location: this.location(String(location), root, url).toString() } : {}) } as T;
      }
      const pending = awaitCompletion && (status === 202 || (polling && ['open', 'running'].includes(String(value?.status))));
      if (!pending) {
        if (polling && ['failed', 'abandoned'].includes(String(value?.status))) {
          throw new NodeOperationError(this.context.getNode(), `Run ${String(value.status)}`, { description: JSON.stringify(value) });
        }
        return (polling ? { ...initial, ...value } : value) as T;
      }
      if (!polling) { initial = value ?? {}; deadline = Date.now() + POLLING_BUDGET_MS; }
      const location = response.headers?.location ?? response.headers?.Location ?? (typeof value?.location === 'string' ? value.location : undefined);
      if (location) url = this.location(String(location), root, url);
      else if (!polling) throw new NodeOperationError(this.context.getNode(), 'Async response has no Location');
      if (url.origin !== root.origin || !url.pathname.startsWith(root.pathname) || url.username || url.password) {
        throw new NodeOperationError(this.context.getNode(), 'Unsafe async Location from automation door');
      }
      const delay = pollingDelay(response.headers);
      if (attempt === 6 || Date.now() + delay >= deadline) break;
      polling = true;
      await sleep(delay);
    }
    const error = new NodeOperationError(this.context.getNode(), 'Automation request exceeded its 30 second polling budget');
    error.context = { runId: flight.runId ?? initial.runId, location: url.toString() };
    throw error;
  }

  private location(location: string, root: URL, current: URL): URL {
    // The server emits root-relative /automation Locations even behind a gateway prefix.
    const target = location.startsWith('/automation/')
      ? new URL(location.slice('/automation/'.length), root) : new URL(location, current);
    if (target.origin !== root.origin || !target.pathname.startsWith(root.pathname) || target.username || target.password) {
      throw new NodeOperationError(this.context.getNode(), 'Unsafe async Location from automation door');
    }
    return target;
  }

  descriptor() { return this.request<Descriptor>('GET', ''); }
  recipe(recipe: string) { return this.request('GET', `recipes/${enc(recipe)}`); }
  open(recipe: string, input: OpenInput) { return this.request<OpenResult>('POST', `recipes/${enc(recipe)}/runs`, input, undefined, false, { opening: true, idempotencyKey: input.idempotencyKey }); }
  part(id: string, name: string) { return this.request('POST', `runs/${enc(id)}/parts/${enc(name)}`); }
  island(id: string, name: string) { return this.request('POST', `runs/${enc(id)}/islands/${enc(name)}`); }
  evaluate(id: string) { return this.request('POST', `runs/${enc(id)}/evaluate`, undefined, undefined, true, { runId: id }); }
  run(id: string) { return this.request('GET', `runs/${enc(id)}`); }
  route(id: string, route: string, offset = 0) { return this.request<Page>('GET', `runs/${enc(id)}/routes/${enc(route)}`, undefined, { offset }); }
  async routeRows(id: string, route: string): Promise<IDataObject[]> {
    const rows: IDataObject[] = [];
    for (let page = 0; page < 10_000; page++) {
      const next = await this.route(id, route, rows.length);
      rows.push(...next.rows);
      if (!next.truncated || rows.length >= next.rowCount) return rows;
      if (!next.rows.length) throw new NodeOperationError(this.context.getNode(), 'Route pagination made no progress');
    }
    throw new NodeOperationError(this.context.getNode(), 'Route pagination exceeded its page budget');
  }
  // Reserved client surface for P1; not currently exposed as Run operations.
  routeDetails(id: string, route: string, context: string, query: IDataObject = {}) { return this.request<Page>('GET', `runs/${enc(id)}/routes/${enc(route)}/details/${enc(context)}`, undefined, query); }
  output(id: string, output: string, query: IDataObject = {}) { return this.request('GET', `runs/${enc(id)}/outputs/${enc(output)}`, undefined, { ...query, format: 'json' }); }
  perform(id: string, action: string, input: IDataObject = {}) { return this.request('POST', `runs/${enc(id)}/actions/${enc(action)}/perform`, input); }
  outcome(id: string, action: string, outcomes: IDataObject[]) { return this.request('POST', `runs/${enc(id)}/actions/${enc(action)}/outcome`, { outcomes }); }
  digests(id: string) { return this.request('GET', `runs/${enc(id)}/digests`); }
  close(id: string) { return this.request('POST', `runs/${enc(id)}/close`); }
  protocol(id: string) { return this.request('GET', `runs/${enc(id)}/protocol`); }
  importRuns(input: IDataObject) { return this.request('POST', 'runs/import', input); }
  windowEvents(recipe: string, program: string, events: IDataObject[]) { return this.request('POST', `windows/${enc(recipe)}/${enc(program)}/events`, { events }); }
  windows(recipe: string, program: string) { return this.request('GET', `windows/${enc(recipe)}/${enc(program)}`); }
  tasks(query: IDataObject = {}) { return this.request('GET', 'tasks', undefined, query); }
  task(id: string) { return this.request('GET', `tasks/${enc(id)}`); }
  createTask(input: IDataObject) { return this.request('POST', 'tasks', input); }
  answer(id: string, input: IDataObject) { return this.request('POST', `tasks/${enc(id)}/answer`, input); }
  webhooks() { return this.request<{ webhooks: IDataObject[] }>('GET', 'webhooks'); }
  createWebhook(input: IDataObject) { return this.request<{ id: string }>('POST', 'webhooks', input); }
  deleteWebhook(id: string) { return this.request('DELETE', `webhooks/${enc(id)}`); }
  event(input: IDataObject) { return this.request('POST', 'webhooks/events', input); }
  runs(query: IDataObject) { return this.request<{ runs: IDataObject[]; truncated?: boolean }>('GET', 'runs', undefined, query); }
}
