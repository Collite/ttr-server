// SPDX-License-Identifier: Apache-2.0
// Validate every version before exposing values to the publishing jobs.
const fs = require('node:fs');
try {
  const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'));
  if (pkg.name !== '@collite/n8n-nodes-tatrman') throw new Error('Unexpected connector package name');
  if (!/^\d+\.\d+\.\d+$/.test(pkg.version)) throw new Error('Package version must be an exact x.y.z');
  const base = fs.readFileSync('Dockerfile', 'utf8').match(/^FROM ghcr\.io\/collite\/n8n:(\d+\.\d+\.\d+)\s*$/m);
  if (!base) throw new Error('Pin the approved n8n mirror as ghcr.io/collite/n8n:x.y.z');
  const expected = `n8n-nodes-tatrman/v${pkg.version}`;
  if (process.env.RELEASE_TAG && process.env.RELEASE_TAG !== expected) throw new Error(`Release tag must be ${expected}`);
  if (process.argv[2] === '--runtime-image') { console.log(`ghcr.io/collite/n8n:${base[1]}`); process.exit(0); }
  if (process.argv[2] === '--runtime-version') { console.log(base[1]); process.exit(0); }
  console.log(`runtime_version=${base[1]}`);
  console.log(`image=ghcr.io/collite/n8n-tatrman:${base[1]}-${pkg.version}`);
  console.log(`package_version=${pkg.version}`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
