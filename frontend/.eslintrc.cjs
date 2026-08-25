// ESLint config — LumiPOS frontend
// Goal: catch the crash classes we have actually shipped (TDZ / undefined
// identifiers / hook ordering) while staying pragmatic about style.
module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  settings: { react: { version: 'detect' } },
  ignorePatterns: ['dist', 'node_modules', 'public/vendor'],
  plugins: ['react', 'react-hooks', 'import'],
  extends: [
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  rules: {
    // ---- The crash-prevention core (these stay as ERRORS forever) ----
    'no-undef': 'error',
    'no-use-before-define': ['error', { functions: false, classes: false, variables: true }],
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',
    'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    'import/no-duplicates': 'warn',

    // ---- React pragmatics (this codebase predates the new JSX transform) ----
    'react/react-in-jsx-scope': 'off',
    'react/prop-types': 'off',
    'react/display-name': 'off',
    'react/no-unescaped-entities': 'off',

    // ---- Style: leave to humans, not the linter ----
    semi: 'off',
    quotes: 'off',
    indent: 'off',
    'comma-dangle': 'off',
    'object-curly-spacing': 'off',
    eqeqeq: ['warn', 'smart'],
  },
};
