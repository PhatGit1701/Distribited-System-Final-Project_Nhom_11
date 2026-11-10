import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: '../target/generated-resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8081',
      '/ws': { target: 'ws://localhost:8081', ws: true },
    },
  },
});
