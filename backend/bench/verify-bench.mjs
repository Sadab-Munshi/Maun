// Rough CPU cost of one signature check, to compare with the Workers free
// plan's 10 ms CPU limit per request. Runs in Node (needs Node 24+), which uses
// the same V8 engine as Workers; the real number shows up as "CPU time" in the
// Cloudflare dashboard once deployed.
//
//   npm run bench

import { readFileSync } from "node:fs";
import { verifyDeviceSignature, verifyIdentitySignature } from "../src/crypto/signatures.ts";

const fixtures = JSON.parse(readFileSync(new URL("../test/fixtures/signatures.json", import.meta.url), "utf8"));
const b64 = (text) => new Uint8Array(Buffer.from(text, "base64"));
const identity = fixtures.identity.map((v) => [b64(v.publicKey), b64(v.message), b64(v.signature)]);
const device = fixtures.device.map((v) => [b64(v.spki), b64(v.message), b64(v.signature)]);

async function measure(name, verify, vectors) {
  const rounds = 200;
  for (const v of vectors) await verify(...v); // warm up
  const before = process.cpuUsage();
  for (let i = 0; i < rounds; i++) {
    for (const v of vectors) {
      if (!(await verify(...v))) throw new Error(`${name}: verification failed`);
    }
  }
  const used = process.cpuUsage(before);
  const perCheckMs = (used.user + used.system) / 1000 / (rounds * vectors.length);
  console.log(`${name.padEnd(28)} ${perCheckMs.toFixed(3)} ms CPU per check`);
  return perCheckMs;
}

const a = await measure("identity key (XEdDSA)", verifyIdentitySignature, identity);
const b = await measure("device key (P-256 ECDSA)", verifyDeviceSignature, device);
console.log(`${"registration (both checks)".padEnd(28)} ${(a + b).toFixed(3)} ms CPU, limit is 10 ms`);
