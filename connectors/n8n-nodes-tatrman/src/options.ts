// SPDX-License-Identifier: Apache-2.0
import type { IDataObject, ILoadOptionsFunctions, INodePropertyOptions } from 'n8n-workflow';
import { AutomationClient } from './client';
const options = (entries: IDataObject[]): INodePropertyOptions[] => entries.map(entry => ({ name: String(entry.name), value: String(entry.name) }));
export const loadOptions = {
  async getDeployments(this: ILoadOptionsFunctions) { return options((await new AutomationClient(this).descriptor()).deployments); },
  async getRecipes(this: ILoadOptionsFunctions) { return options((await new AutomationClient(this).descriptor()).recipes); },
  async getRoutes(this: ILoadOptionsFunctions) {
    const detail = await new AutomationClient(this).recipe(String(this.getNodeParameter('recipe', '')));
    const recipe = (detail.recipe ?? detail) as IDataObject;
    return options(((recipe.roles as IDataObject)?.routes ?? []) as IDataObject[]);
  },
  async getActions(this: ILoadOptionsFunctions) {
    const detail = await new AutomationClient(this).recipe(String(this.getNodeParameter('recipe', '')));
    const recipe = (detail.recipe ?? detail) as IDataObject;
    return [...new Set(((recipe.wiring ?? []) as IDataObject[]).map(wire => String(wire.action)))].map(name => ({ name, value: name }));
  },
};
