import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// The same relay with the two settings that make it member-only.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: { bindings: { ACCESS_TEAM: "team.test", ACCESS_AUD: "aud-of-this-relay" } },
    }),
  ],
  test: { include: ["test/members/*.test.ts"] },
});
