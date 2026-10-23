import { expect, it } from 'vitest';
import { TatrmanAutomationApi } from '../credentials/TatrmanAutomationApi.credentials';
it('tests the health door and applies bearer authentication', () => {
  const credential = new TatrmanAutomationApi();
  expect(credential.test.request).toMatchObject({ url: '/automation/health', method: 'GET' });
  expect(credential.authenticate).toMatchObject({ type: 'generic', properties: { headers: { Authorization: '=Bearer {{$credentials.token}}' } } });
  expect(credential.properties.find(p => p.name === 'token')?.typeOptions?.password).toBe(true);
});
