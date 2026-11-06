const globals = Object.fromEntries(['Buffer','process','console','setTimeout','clearTimeout','URL','AbortSignal','fetch','queueMicrotask','window','document'].map(name => [name, 'readonly']));
export default [{
  files: ['**/*.mjs'], ignores: ['node_modules/**'],
  languageOptions: { ecmaVersion: 'latest', sourceType: 'module', globals },
  rules: { 'no-undef':'error', 'no-unused-vars':['error',{argsIgnorePattern:'^_',caughtErrors:'all',caughtErrorsIgnorePattern:'^_'}],
    'no-unreachable':'error', 'no-constant-condition':'error', 'constructor-super':'error', 'valid-typeof':'error' },
}];
