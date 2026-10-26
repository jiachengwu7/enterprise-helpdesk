const test = require('node:test');
const assert = require('node:assert/strict');
const { assertModelPermission } = require('./model-guard.cjs');

test('keyword-only service permits offline browser acceptance', () => {
  assert.doesNotThrow(() => assertModelPermission({status:'ok',modelConfigured:false,embeddingConfigured:false}));
});
test('embedding-only configuration must block before document upload', () => {
  assert.throws(() => assertModelPermission({status:'ok',modelConfigured:false,embeddingConfigured:true}), /allow-model/);
});
test('chat configuration requires explicit opt-in', () => {
  const state = {status:'ok',modelConfigured:true,embeddingConfigured:false};
  assert.throws(() => assertModelPermission(state), /allow-model/);
  assert.doesNotThrow(() => assertModelPermission(state, true));
});
test('missing or unavailable status cannot be interpreted as no-model', () => {
  for (const state of [null,{}, {status:'unavailable'}, {status:'ok',modelConfigured:false}]) {
    assert.throws(() => assertModelPermission(state), /Cannot verify/);
  }
});
