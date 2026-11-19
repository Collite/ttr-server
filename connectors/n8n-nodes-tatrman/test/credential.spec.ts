// SPDX-License-Identifier: Apache-2.0
import { expect, it } from 'vitest';
import { TatrmanAutomationApi } from '../credentials/TatrmanAutomationApi.credentials';
it('tests the authenticated descriptor and applies bearer authentication', () => {
  const credential = new TatrmanAutomationApi();
  expect(credential.test.request).toMatchObject({ url: '/automation/', method: 'GET' });
  expect(credential.authenticate).toMatchObject({ type: 'generic', properties: { headers: { Authorization: '=Bearer {{$credentials.token}}' } } });
  expect(credential.properties.find(p => p.name === 'token')?.typeOptions?.password).toBe(true);
});
