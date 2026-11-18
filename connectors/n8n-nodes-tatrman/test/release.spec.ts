import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

const script = resolve('scripts/release-metadata.cjs');
const directories: string[] = [];
afterEach(() => { for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true }); });
function fixture(version = '0.1.0', base = 'ghcr.io/collite/n8n:2.44.6') {
  const directory = mkdtempSync(join(tmpdir(), 'n8n-release-'));
  directories.push(directory);
  writeFileSync(join(directory, 'package.json'), JSON.stringify({ name: '@collite/n8n-nodes-tatrman', version }));
  writeFileSync(join(directory, 'Dockerfile'), `FROM ${base}\nUSER node\n`);
  return directory;
}
function run(directory: string, tag?: string) {
  return spawnSync(process.execPath, [script], { cwd: directory, encoding: 'utf8', env: { ...process.env, RELEASE_TAG: tag ?? '' } });
}
describe('derived image release metadata', () => {
  it('derives the exact runtime-package image tag from the release inputs', () => {
    const directory = fixture();
    const output = execFileSync(process.execPath, [script], { cwd: directory, encoding: 'utf8', env: { ...process.env, RELEASE_TAG: 'n8n-nodes-tatrman/v0.1.0' } });
    expect(output).toContain('image=ghcr.io/collite/n8n-tatrman:2.44.6-0.1.0\n');
    expect(output).toContain('package_version=0.1.0\n');
  });
  it.each(['ghcr.io/collite/n8n:2.44.6'])('supports PR checks with official base %s without minting a release tag', base => {
    expect(run(fixture('0.1.0', base)).status).toBe(0);
  });
  it.each(['n8n-nodes-tatrman/v0.1.1', 'landing/v0.1.0', 'n8n-nodes-tatrman/v0.1.0-RELEASE'])('refuses mismatched release %s before emitting publish outputs', tag => {
    const result = run(fixture(), tag);
    expect(result.status).not.toBe(0);
    expect(result.stdout).toBe('');
    expect(result.stderr).toContain('Release tag must be n8n-nodes-tatrman/v0.1.0');
  });
  it.each(['ghcr.io/collite/n8n:latest', 'n8nio/n8n:2.44.6', 'docker.n8n.io/n8nio/n8n:2.44.6', 'example.com/n8nio/n8n:2.44.6'])('refuses an unapproved or unpinned base %s', base => {
    const result = run(fixture('0.1.0', base));
    expect(result.status).not.toBe(0);
    expect(result.stdout).toBe('');
    expect(result.stderr).toContain('Pin the approved n8n mirror');
  });
  it('refuses malformed package versions before emitting image outputs', () => {
    const result = run(fixture('0.1.0\nextra=unsafe'));
    expect(result.status).not.toBe(0);
    expect(result.stdout).toBe('');
    expect(result.stderr).toContain('Package version must be');
  });
});
