// ESLint flat config: JS recommended + typescript-eslint (recommended) + React hooks + jsx-a11y (recommended).
// `npm run lint` runs the type-check first, then this. Rules are not disabled wholesale; any inline disable must say why.
import js from '@eslint/js';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended, jsxA11y.flatConfigs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    plugins: { 'react-hooks': reactHooks },
    linterOptions: { reportUnusedDisableDirectives: 'error' },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // Our <Input>, <Textarea> and <Select> render native controls, so a <label> wrapping one is a valid association.
      'jsx-a11y/label-has-associated-control': ['error', { controlComponents: ['Input', 'Textarea', 'Select'], assert: 'either', depth: 3 }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' }],
    },
  },
  {
    // Node-side files: Playwright suite and tool configs.
    files: ['e2e/**/*.ts', '*.config.{js,ts}', 'playwright.config.ts', 'pwa/**/*.ts'],
    languageOptions: { globals: { ...globals.node } },
  },
);
