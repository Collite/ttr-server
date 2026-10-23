import { vi } from 'vitest';
import type { IExecuteFunctions, IDataObject, IHttpRequestOptions, INode } from 'n8n-workflow';

export const node = { id: 'test', name: 'Tatrman', type: 'tatrmanRun', typeVersion: 1, position: [0, 0], parameters: {} } as INode;
export function harness(parameters: IDataObject, respond: (options: IHttpRequestOptions) => unknown = () => ({}), count = 1) {
  const state: IDataObject = {};
  const request = vi.fn(async (_credential: string, options: IHttpRequestOptions) => {
    const body = await respond(options);
    if (body && typeof body === 'object' && 'statusCode' in body) return body;
    return { statusCode: 200, headers: {}, body };
  });
  const context = {
    getNode: () => node,
    getInputData: () => Array.from({ length: count }, () => ({ json: {} })),
    getNodeParameter: (name: string, _index?: number, fallback?: unknown) => parameters[name] ?? fallback,
    getCredentials: async () => ({ baseUrl: 'https://fake.test', token: 'secret' }),
    getWorkflowStaticData: () => state,
    getMode: () => 'trigger', getActivationMode: () => 'activate',
    continueOnFail: () => false,
    helpers: { httpRequestWithAuthentication: request },
  } as unknown as IExecuteFunctions;
  return { context, request, state };
}
