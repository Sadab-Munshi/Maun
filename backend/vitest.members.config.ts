import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// The same relay with the settings that make it member-only.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: { ADMIN_EMAIL: "Boss@Example.com", RESEND_API_KEY: "re_test_key", MAIL_FROM: "Maun <login@maun.test>" },
      },
    }),
  ],
  test: { include: ["test/members/*.test.ts"] },
});
