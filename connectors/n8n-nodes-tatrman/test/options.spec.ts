// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import type { ILoadOptionsFunctions } from 'n8n-workflow';
import { loadOptions } from '../src/options';
import { harness } from './harness';
const fixture = (name: string) => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));
it('loads deployments and recipes from the Server descriptor fixture', async () => {
  const descriptor = fixture('descriptor');
  const h = harness({}, () => descriptor);
  expect(await loadOptions.getDeployments.call(h.context as unknown as ILoadOptionsFunctions)).toContainEqual({ name: descriptor.deployments[0].name, value: descriptor.deployments[0].name });
  expect(await loadOptions.getRecipes.call(h.context as unknown as ILoadOptionsFunctions)).toContainEqual({ name: 'release_order', value: 'release_order' });
});
it('loads routes and deduplicated actions from the actual H-1 manifest recipe', async () => {
  const h = harness({ recipe: 'release_order' }, () => ({ bundle: 'served:test', recipe: fixture('release_order'), approvals: [] }));
  expect(await loadOptions.getRoutes.call(h.context as unknown as ILoadOptionsFunctions)).toContainEqual({ name: 'hold_sales', value: 'hold_sales' });
  const actions = await loadOptions.getActions.call(h.context as unknown as ILoadOptionsFunctions);
  expect(actions).toContainEqual({ name: 'notify', value: 'notify' });
  expect(actions.filter(x => x.value === 'notify')).toHaveLength(1);
});
