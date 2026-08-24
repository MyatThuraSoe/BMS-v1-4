import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:17234',
        changeOrigin: true
      }
    }
  },
  build: {
    // Route-level code splitting keeps pages lazy; these groups pin the big
    // shared libraries into cacheable chunks so app updates re-download only
    // the small index bundle.
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          'vendor-mui': ['@mui/material', '@mui/icons-material', '@emotion/react', '@emotion/styled'],
          'vendor-charts': ['recharts'],
          'vendor-data': ['@tanstack/react-query', 'axios'],
          'vendor-i18n': ['i18next', 'react-i18next'],
        },
      },
    },
  },
})
