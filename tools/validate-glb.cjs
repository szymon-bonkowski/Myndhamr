// Optional independent Khronos validator. Install the pinned development tool:
// npm install --prefix build/gltf-validation gltf-validator@2.0.0-dev.3.10
const fs = require('node:fs');
const validator = require('../build/gltf-validation/node_modules/gltf-validator');
if (process.argv.length !== 3) throw new Error('Usage: node tools/validate-glb.cjs <mesh.glb>');
validator.validateBytes(new Uint8Array(fs.readFileSync(process.argv[2])), {
  uri: process.argv[2], maxIssues: 100,
}).then(report => {
  process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  if (report.issues.numErrors) process.exitCode = 1;
}).catch(error => { process.stderr.write(String(error) + '\n'); process.exitCode = 2; });
