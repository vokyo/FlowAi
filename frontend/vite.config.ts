import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    // Defaults to 5173, but yields to PORT so a second dev server can run
    // alongside one that already holds the default. strictPort stays on for the
    // default case, where a silent port shift would break the proxy assumptions.
    port: Number(process.env.PORT ?? 5173),
    strictPort: !process.env.PORT,
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})

