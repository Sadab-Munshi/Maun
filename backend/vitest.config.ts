import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// The relay as it is deployed without any settings: open to everyone.
// Member-only mode is tested by vitest.members.config.ts.
export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.toml" } })],
  test: { include: ["test/*.test.ts"] },
});
