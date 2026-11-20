// SPDX-License-Identifier: Apache-2.0
import { NodeApiError, NodeOperationError, sleep } from 'n8n-workflow';
import type { IDataObject, IExecuteFunctions, IHttpRequestOptions, ILoadOptionsFunctions, IPollFunctions, INode, JsonObject } from 'n8n-workflow';

type Context = IExecuteFunctions | ILoadOptionsFunctions | IPollFunctions;
export interface Page extends IDataObject { rows: IDataObject[]; rowCount: number; truncated?: boolean }
export interface OpenInput extends IDataObject { deployment: string; port: IDataObject; asOf?: string; bundle?: string; dryRun?: boolean; idempotencyKey?: string; origin?: string }
export interface OpenResult extends IDataObject { runId: string; replayed?: boolean; status?: string }
export interface Descriptor extends IDataObject { deployments: IDataObject[]; recipes: IDataObject[]; programs: IDataObject[] }
interface RunListing extends IDataObject { runId: string; status: string; startedAt: string; protocolRef: string }
// Server defaults: syncDefault=60 s, batchDefault=30 min. These configurable client
// defaults allow sync headroom and ten minutes of polling; programs may need larger values.
export const DEFAULT_REQUEST_TIMEOUT_SECONDS = 90;
export const DEFAULT_WAIT_UP_TO_SECONDS = 600;
export interface ClientBudgets { requestTimeoutMs?: number; waitUpToMs?: number }
interface Flight { runId?: string; opening?: boolean; idempotencyKey?: string; read?: string }
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
  constructor(private readonly context: Context, private readonly budgets: ClientBudgets = {}) {}

  async request<T = IDataObject>(method: IHttpRequestOptions['method'], path: string, body?: IDataObject, qs?: IDataObject, awaitCompletion = false, flight: Flight = {}): Promise<T> {
    const credentials = await this.context.getCredentials('tatrmanAutomationApi');
    const root = new URL(`${String(credentials.baseUrl).replace(/\/+$/, '')}/automation/`);
    if (root.username || root.password) throw new NodeOperationError(this.context.getNode(), 'Base URL must not contain credentials');
    let url = new URL(path.replace(/^\//, ''), root);
    const requestTimeout = this.budgets.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_SECONDS * 1000;
    const waitUpTo = this.budgets.waitUpToMs ?? DEFAULT_WAIT_UP_TO_SECONDS * 1000;
    let deadline = Infinity;
    let polling = false;
    let initial: IDataObject = {};
    while (true) {
      let response;
      try {
        response = await this.context.helpers.httpRequestWithAuthentication.call(this.context, 'tatrmanAutomationApi', {
          method: polling ? 'GET' : method, url: url.toString(), json: true,
          ...(polling ? {} : { ...(body === undefined ? {} : { body }), ...(qs ? { qs } : {}) }),
          returnFullResponse: true, ignoreHttpStatusErrors: true, disableFollowRedirect: true,
          timeout: polling ? Math.max(1, Math.min(requestTimeout, deadline - Date.now())) : requestTimeout,
        });
      } catch {
        // Do not expose a helper error's request configuration, which may contain the bearer.
        const description = this.flightDescription(flight);
        const error = new NodeApiError(this.context.getNode(), {}, { message: 'Tatrman request failed', description });
        error.context = { runId: flight.runId ?? initial.runId, location: url.toString() };
        throw error;
      }
      const status = Number(response.statusCode);
      const value = response.body as IDataObject;
      const runId = flight.runId ?? initial.runId;
      if (status >= 400) {
        const fault = value && typeof value === 'object' ? value.error as IDataObject | undefined : undefined;
        const gatewayFailure = [502, 503, 504].includes(status) && !fault?.code;
        const code = String(fault?.code ?? status);
        const error = new NodeApiError(this.context.getNode(), (value && typeof value === 'object' ? value : {}) as JsonObject, {
          message: `${code}: ${String(fault?.message ?? 'Tatrman request failed')}`,
          description: gatewayFailure ? this.flightDescription(flight) : JSON.stringify(fault?.detail ?? value), httpCode: String(status),
        });
        error.context = { code, detail: fault?.detail, runId, location: url.toString() };
        throw error;
      }
      if (status >= 300 && status < 400) {
        throw new NodeOperationError(this.context.getNode(), 'Unexpected redirect from the automation door');
      }
      if (flight.opening) {
        const location = response.headers?.location ?? response.headers?.Location ?? value?.location;
        return { ...value, ...(location ? { location: this.location(String(location), root, url).toString() } : {}) } as T;
      }
      const pending = awaitCompletion && (status === 202 || (polling && ['open', 'running'].includes(String(value?.status))));
      if (!pending) {
        if (polling && ['failed', 'abandoned'].includes(String(value?.status))) {
          throw new NodeOperationError(this.context.getNode(), `Run ${String(value.status)}`, { description: JSON.stringify(value) });
        }
        return (polling ? { ...initial, ...value } : value) as T;
      }
      if (!polling) { initial = value ?? {}; deadline = Date.now() + waitUpTo; }
      const location = response.headers?.location ?? response.headers?.Location ?? (typeof value?.location === 'string' ? value.location : undefined);
      if (location) url = this.location(String(location), root, url);
      else if (!polling) throw new NodeOperationError(this.context.getNode(), 'Async response has no Location');
      const delay = pollingDelay(response.headers);
      if (Date.now() + delay >= deadline) break;
      polling = true;
      await sleep(delay);
    }
    const error = new NodeOperationError(this.context.getNode(), `Automation request exceeded its ${waitUpTo / 1000} second polling budget`, { description: this.flightDescription(flight) });
    error.context = { runId: flight.runId ?? initial.runId, location: url.toString() };
    throw error;
  }

  private flightDescription(flight: Flight): string {
    if (flight.read) return `Read ${flight.read} for run ${flight.runId} timed out or could not complete; the server read may continue.`;
    if (flight.runId) return `The evaluation for run ${flight.runId} continues on the server if accepted. Retry Evaluate with this run ID to join it.`;
    if (flight.opening) return flight.idempotencyKey
      ? 'The run may already exist; retry Open with the same key.'
      : 'The run may already exist; a retry opens another run. Always set an Idempotency Key before the first attempt.';
    return 'The automation door could not be reached';
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
  part(id: string, name: string) { return this.request('POST', `runs/${enc(id)}/parts/${enc(name)}`, undefined, undefined, false, { runId: id, read: `part ${name}` }); }
  island(id: string, name: string) { return this.request('POST', `runs/${enc(id)}/islands/${enc(name)}`, undefined, undefined, false, { runId: id, read: `island ${name}` }); }
  evaluate(id: string) { return this.request('POST', `runs/${enc(id)}/evaluate`, undefined, undefined, true, { runId: id }); }
  run(id: string) { return this.request('GET', `runs/${enc(id)}`, undefined, undefined, false, { runId: id, read: 'run status' }); }
  route(id: string, route: string, offset = 0) { return this.request<Page>('GET', `runs/${enc(id)}/routes/${enc(route)}`, undefined, { offset }, false, { runId: id, read: `route ${route}` }); }
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
  runs(query: IDataObject) { return this.request<{ runs: RunListing[]; next: string | null }>('GET', 'runs', undefined, query); }
}
