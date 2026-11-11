import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: '../target/generated-resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // Keep the browser Host so NodeSessionFilter can verify Origin through the dev proxy.
      '/api': { target: 'http://127.0.0.1:8081', changeOrigin: false },
      '/ws': { target: 'ws://localhost:8081', ws: true },
    },
  },
});
