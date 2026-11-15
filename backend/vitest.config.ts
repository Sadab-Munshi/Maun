import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// The relay as it is deployed without member settings: open to everyone.
// Calls are set up (with a made-up key; the tests stand in for the service).
// Member-only mode is tested by vitest.members.config.ts.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: { bindings: { TURN_KEY_ID: "test-turn-key", TURN_API_TOKEN: "test-turn-token" } },
    }),
  ],
  test: { include: ["test/*.test.ts"] },
});
