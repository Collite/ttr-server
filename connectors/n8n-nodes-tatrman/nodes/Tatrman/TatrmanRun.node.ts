import { NodeApiError, NodeConnectionTypes, NodeOperationError } from 'n8n-workflow';
import type { IDataObject, IExecuteFunctions, INodeExecutionData, INodeProperties, INodeType, INodeTypeDescription, JsonObject } from 'n8n-workflow';
import { AutomationClient } from '../../src/client';
import { loadOptions } from '../../src/options';
const operations = ['open', 'part', 'islands', 'evaluate', 'routes', 'perform', 'outcome', 'digests', 'close', 'answer', 'task'];
const show = (...operation: string[]) => ({ show: { operation } });
const field = (displayName: string, name: string, description: string, operation: string[], extra: Partial<INodeProperties> = {}): INodeProperties => ({ displayName, name, description, type: 'string', default: '', required: true, displayOptions: show(...operation), ...extra });
const runOperations = ['part', 'islands', 'evaluate', 'routes', 'perform', 'outcome', 'digests', 'close'];
export class TatrmanRun implements INodeType {
  description: INodeTypeDescription = {
    displayName: 'Tatrman Run', name: 'tatrmanRun', icon: { light: 'file:tatrman.svg', dark: 'file:tatrman.svg' }, group: ['transform'], version: 1,
    subtitle: '={{$parameter["operation"]}}', description: 'Run governed Tatrman automation recipes',
    usableAsTool: true, defaults: { name: 'Tatrman Run' }, inputs: [NodeConnectionTypes.Main], outputs: [NodeConnectionTypes.Main],
    credentials: [{ name: 'tatrmanAutomationApi', required: true }],
    properties: [
      { displayName: 'Operation', name: 'operation', type: 'options', noDataExpression: true, default: 'open', options: operations.map(value => ({ name: value[0].toUpperCase() + value.slice(1), value, action: `${value[0].toUpperCase() + value.slice(1)} a run` })) },
      field('Recipe Name or ID', 'recipe', 'Choose from the list, or specify an ID using an expression', ['open', 'routes', 'perform', 'outcome'], { type: 'options', typeOptions: { loadOptionsMethod: 'getRecipes' } }),
      field('Deployment Name or ID', 'deployment', 'Choose from the list, or specify an ID using an expression', ['open'], { type: 'options', typeOptions: { loadOptionsMethod: 'getDeployments' } }),
      field('Run ID', 'runId', 'Run returned by Open', runOperations),
      field('Port', 'port', 'JSON object: {"none":true}, {"key":…}, {"keys":[…]}, {"event":{…}} or {"window":{…}}', ['open'], { type: 'json', default: '{"none":true}' }),
      field('As Of', 'asOf', 'Optional replay instant or now (requires debug or dry run)', ['open'], { required: false }),
      field('Bundle', 'bundle', 'Optional preview bundle (debug only)', ['open'], { required: false }),
      field('Dry Run', 'dryRun', 'Whether to request a dry run', ['open'], { type: 'boolean', default: false, required: false }),
      field('Idempotency Key', 'idempotencyKey', 'Stable key for replaying an open request safely', ['open'], { required: false }),
      field('Part', 'part', 'Candidates, a context name, or decision', ['part'], { default: 'candidates' }),
      field('Island', 'island', 'Island name in the recipe manifest', ['islands']),
      field('Route Name or ID', 'route', 'Choose from the list, or specify an ID using an expression', ['routes'], { type: 'options', typeOptions: { loadOptionsMethod: 'getRoutes', loadOptionsDependsOn: ['recipe'] } }),
      field('Action Name or ID', 'action', 'Choose from the list, or specify an ID using an expression', ['perform', 'outcome'], { type: 'options', typeOptions: { loadOptionsMethod: 'getActions', loadOptionsDependsOn: ['recipe'] } }),
      field('Keys', 'keys', 'Optional JSON array of candidate keys', ['perform'], { type: 'json', default: '', required: false }),
      field('Group', 'group', 'Optional JSON group value', ['perform'], { type: 'json', default: '', required: false }),
      field('Callback URL', 'callbackUrl', 'Optional URL for the action completion callback', ['perform'], { required: false }),
      field('Outcomes', 'outcomes', 'JSON array of {key, status, at, detail?} records', ['outcome'], { type: 'json', default: '[]' }),
      field('Task ID', 'taskId', 'Task to fetch or answer', ['task', 'answer']),
      field('Answer', 'answer', 'Approve, reject, or a task option', ['answer'], { default: 'approve' }),
      field('Note', 'note', 'Optional note from the answerer', ['answer'], { required: false }),
      field('Rows', 'rows', 'Optional JSON array of answer rows', ['answer'], { type: 'json', default: '', required: false }),
    ],
  };
  methods = { loadOptions };
  async execute(this: IExecuteFunctions): Promise<INodeExecutionData[][]> {
    const client = new AutomationClient(this);
    const output: INodeExecutionData[] = [];
    for (let i = 0; i < this.getInputData().length; i++) {
      const value = (name: string, fallback: unknown = '') => this.getNodeParameter(name, i, fallback);
      const str = (name: string) => String(value(name));
      const json = (name: string, kind?: 'array' | 'object'): unknown => {
        const raw = value(name);
        let parsed;
        try { parsed = typeof raw === 'string' ? JSON.parse(raw) : raw; }
        catch { throw new NodeOperationError(this.getNode(), `Invalid ${name[0].toUpperCase() + name.slice(1)} JSON`, { itemIndex: i }); }
        if ((kind === 'array' && !Array.isArray(parsed)) || (kind === 'object' && (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)))) {
          throw new NodeOperationError(this.getNode(), `${name} must be a JSON ${kind}`, { itemIndex: i });
        }
        return parsed;
      };
      try {
        const id = str('runId');
        let result: IDataObject | IDataObject[];
        switch (str('operation')) {
          case 'open': {
            const body = { deployment: str('deployment'), port: json('port', 'object') as IDataObject, dryRun: Boolean(value('dryRun', false)), origin: 'engine' };
            const extras: IDataObject = {};
            for (const key of ['asOf', 'bundle', 'idempotencyKey']) if (str(key)) extras[key] = str(key);
            result = await client.open(str('recipe'), { ...body, ...extras }); break;
          }
          case 'part': result = await client.part(id, str('part')); break;
          case 'islands': result = await client.island(id, str('island')); break;
          case 'evaluate': result = await client.evaluate(id); break;
          case 'routes': result = await client.routeRows(id, str('route')); break;
          case 'perform': {
            const body: IDataObject = {};
            if (str('keys')) body.keys = json('keys', 'array') as IDataObject[];
            if (str('group')) body.group = json('group') as IDataObject;
            if (str('callbackUrl')) body.callbackUrl = str('callbackUrl');
            result = await client.perform(id, str('action'), body); break;
          }
          case 'outcome': result = await client.outcome(id, str('action'), json('outcomes', 'array') as IDataObject[]); break;
          case 'digests': result = await client.digests(id); break;
          case 'close': result = await client.close(id); break;
          case 'answer': {
            const body: IDataObject = { answer: str('answer') };
            if (str('note')) body.note = str('note');
            if (str('rows')) body.rows = json('rows', 'array') as IDataObject[];
            result = await client.answer(str('taskId'), body); break;
          }
          case 'task': result = await client.task(str('taskId')); break;
          default: throw new NodeOperationError(this.getNode(), 'Unknown Tatrman Run operation', { itemIndex: i });
        }
        for (const row of Array.isArray(result) ? result : [result]) output.push({ json: row ?? {}, pairedItem: { item: i } });
      } catch (error) {
        if (!this.continueOnFail()) throw new NodeApiError(this.getNode(), error as JsonObject, { itemIndex: i });
        const fault = error as Error & { context?: IDataObject };
        output.push({ json: { error: fault.message, code: fault.context?.code, detail: fault.context?.detail }, pairedItem: { item: i } });
      }
    }
    return [output];
  }
}
