function assertModelPermission(status, allowModel = false) {
  if (!status || status.status !== 'ok' || typeof status.modelConfigured !== 'boolean' || typeof status.embeddingConfigured !== 'boolean') {
    throw new Error('Cannot verify chat/embedding configuration; refusing writes');
  }
  if ((status.modelConfigured || status.embeddingConfigured) && !allowModel) {
    throw new Error('Chat/embedding configured: explicit --allow-model required before upload or chat');
  }
}
module.exports = { assertModelPermission };
