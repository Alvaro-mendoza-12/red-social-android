import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores([
    'dist',
    'tmp',
    'output',
    'coverage',
    'src/__tests__',
    'src/components/advanced',
    'src/components/EnterpriseLayout.tsx',
    'src/components/ErrorBoundary.tsx',
    'src/core/api/EnterpriseApiClient.ts',
    'src/core/hooks/useWebRTC.ts',
    'src/core/pwa/ServiceWorker.ts',
    'src/features/realtime',
    'src/features/voice/WebRTCManager.ts',
    'src/ml',
    'src/pages/enterprise',
    'src/pages/optimized',
  ]),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
  },
])
