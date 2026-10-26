import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5180,
    strictPort: true,
    proxy: { "/api": { target: "http://127.0.0.1:8180", changeOrigin: true } },
  },
  test: { environment: "jsdom" },
});
