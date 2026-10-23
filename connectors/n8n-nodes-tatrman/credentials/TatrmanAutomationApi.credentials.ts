import type { IAuthenticateGeneric, ICredentialTestRequest, ICredentialType, INodeProperties, Icon } from 'n8n-workflow';

export class TatrmanAutomationApi implements ICredentialType {
  name = 'tatrmanAutomationApi';
  icon: Icon = { light: 'file:../nodes/Tatrman/tatrman.svg', dark: 'file:../nodes/Tatrman/tatrman.svg' };
  displayName = 'Tatrman Automation API';
  documentationUrl = 'https://github.com/Collite/kantheon/tree/master/connectors/n8n-nodes-tatrman#credentials';
  properties: INodeProperties[] = [
    { displayName: 'Base URL', name: 'baseUrl', type: 'string', default: '', required: true, placeholder: 'https://automation.example.com', description: 'Automation service URL, without /automation or a trailing slash' },
    { displayName: 'Token', name: 'token', type: 'string', typeOptions: { password: true }, default: '', required: true, description: 'Deployment bearer token, or a person’s token for answers and personal queries' },
    { displayName: 'Iris BFF URL', name: 'irisBffUrl', type: 'string', default: '', description: 'Optional Iris BFF URL for Golem Question (P1)' },
  ];
  authenticate: IAuthenticateGeneric = { type: 'generic', properties: { headers: { Authorization: '=Bearer {{$credentials.token}}' } } };
  test: ICredentialTestRequest = { request: { baseURL: '={{$credentials.baseUrl}}', url: '/automation/health', method: 'GET' } };
}
