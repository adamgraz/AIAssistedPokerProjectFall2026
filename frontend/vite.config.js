import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Bind to every network interface, not just localhost, so another device on the same
  // wifi (a phone at the table) can load the page from this machine's LAN IP.
  server: { host: true },
})
