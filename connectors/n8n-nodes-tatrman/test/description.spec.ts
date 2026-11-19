// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { TatrmanTrigger } from '../nodes/Tatrman/TatrmanTrigger.node';
import { TatrmanRun } from '../nodes/Tatrman/TatrmanRun.node';
import { TatrmanAutomationApi } from '../credentials/TatrmanAutomationApi.credentials';
describe('n8n descriptions', () => {
  it('defines a valid Run description and stable parameter forms', () => {
    const d = new TatrmanRun().description;
    expect(d).toMatchObject({ name: 'tatrmanRun', displayName: 'Tatrman Run', version: 1, inputs: ['main'], outputs: ['main'], group: ['transform'] });
    expect(d.defaults.name).toBeTruthy();
    expect(new Set(d.properties.map(p => p.name)).size).toBe(d.properties.length);
    expect(d.properties).toMatchSnapshot();
  });
  it('defines a valid Trigger description and stable parameter forms', () => {
    const d = new TatrmanTrigger().description;
    expect(d).toMatchObject({ name: 'tatrmanTrigger', version: 1, inputs: [], outputs: ['main'], polling: true });
    expect(d.properties).toMatchSnapshot();
  });
  it('has unique credential fields and the required identity fields', () => {
    const d = new TatrmanAutomationApi();
    expect(d.name).toBe('tatrmanAutomationApi');
    expect(d.properties.map(p => p.name)).toEqual(['baseUrl', 'token', 'irisBffUrl']);
  });
});
