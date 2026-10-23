import { buildScanConfig } from '@n8n/scan-community-package/scanner/scanner.mjs';
import { ESLint } from 'eslint';
// Two public-community policies conflict with the owning AU contracts: Platform licensing (D-43)
// and an exact SDK peer pin (S0.1 T1). All security and node API rules remain enabled.
const eslint = new ESLint({
  cwd: process.cwd(), overrideConfigFile: true, allowInlineConfig: false,
  overrideConfig: [...await buildScanConfig(), {
    files: ['package.json'], rules: {
      'n8n-nodes-base/community-package-json-license-not-default': 'off',
      '@n8n/community-nodes/valid-peer-dependencies': 'off',
    },
  }],
});
const results = await eslint.lintFiles(['package.json', '{nodes,credentials,src}/**/*.ts', 'dist/**/*.js']);
console.log((await eslint.loadFormatter('stylish')).format(results));
if (results.some(result => result.errorCount)) process.exitCode = 1;
else console.log('n8n source + distribution checks passed (documented private licence / pinned peer policy exceptions)');
