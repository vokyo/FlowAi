import js from '@eslint/js'
import globals from 'globals'
import checkFile from 'eslint-plugin-check-file'
import importX from 'eslint-plugin-import-x'
import { createTypeScriptImportResolver } from 'eslint-import-resolver-typescript'
import { readdirSync } from 'node:fs'

// project-shell is the composition root: it wires features into routes, so it is
// the one feature allowed to reach sideways. Every other feature may only import
// from itself, plus any documented exception listed here.
const COMPOSITION_ROOT = 'project-shell'
const ALLOWED_CROSS_FEATURE = {
  // The list/board view toggle currently lives inside IssueListFeature, which
  // lazy-loads BoardFeature. Hoisting that switch into project-shell would remove
  // this edge; until then the dependency is declared rather than silent.
  'issue-list': ['board'],
}

const featureBoundaryZones = readdirSync('./src/features', { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && entry.name !== COMPOSITION_ROOT)
  .map((entry) => ({
    target: `./src/features/${entry.name}`,
    from: './src/features',
    except: [entry.name, ...(ALLOWED_CROSS_FEATURE[entry.name] ?? [])].map((n) => `./${n}`),
    message:
      'Cross-feature import. Promote the shared code to src/ui, src/domain, src/api or src/routing.',
  }))
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },

  // Structure guards. These encode the layout the repo already follows, so they
  // stay green today and fail the moment a file drifts out of place.
  {
    files: ['src/**/*.{ts,tsx}'],
    // Multi-component UI modules are kebab-case: components/ui is owned by the
    // shadcn generator, and src/ui holds our own shared kit. Single-component
    // files everywhere else stay PascalCase.
    ignores: ['src/components/ui/**', 'src/ui/**', 'src/main.tsx'],
    plugins: { 'check-file': checkFile },
    rules: {
      'check-file/filename-naming-convention': [
        'error',
        {
          'src/**/*.tsx': 'PASCAL_CASE',
          'src/**/use*.ts': 'CAMEL_CASE',
          'src/**/!(use)*.ts': 'KEBAB_CASE',
        },
        // Lets `foo.test.ts` be judged on `foo`.
        { ignoreMiddleExtensions: true },
      ],
      'check-file/folder-naming-convention': [
        'error',
        { 'src/**/': 'KEBAB_CASE' },
      ],
    },
  },

  // Module boundaries: a feature owns one user-facing capability and may not
  // reach into a sibling. Shared code belongs in ui/, domain/, api/ or routing/.
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'import-x': importX },
    settings: {
      'import-x/resolver-next': [
        createTypeScriptImportResolver({ project: './tsconfig.app.json' }),
      ],
    },
    rules: {
      'import-x/no-cycle': ['error', { maxDepth: Infinity }],
      'import-x/no-restricted-paths': [
        'error',
        {
          zones: [
            ...featureBoundaryZones,
            {
              target: './src/ui',
              from: './src/features',
              message: 'src/ui is shared UI and must not depend on any feature.',
            },
            {
              target: './src/domain',
              from: './src/features',
              message: 'src/domain is shared logic and must not depend on any feature.',
            },
            {
              target: './src/api',
              from: './src/features',
              message: 'src/api is the transport layer and must not depend on any feature.',
            },
          ],
        },
      ],
    },
  },
])
