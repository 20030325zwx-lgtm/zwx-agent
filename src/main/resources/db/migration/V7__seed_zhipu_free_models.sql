-- 智谱 BigModel 免费模型目录：GLM-4.7-Flash（文本）+ GLM-4.6V-Flash（视觉）。
-- 仅预置供应商与模型条目，不自动绑定 chat/vision，避免在未填 API Key 前改变全站默认模型。
-- 模型 ID 与端点以官方文档为准：https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash
--                                          https://docs.bigmodel.cn/cn/guide/models/free/glm-4.6v-flash

INSERT INTO model_provider (owner_id, name, protocol, base_url, secret_ciphertext, secret_hint, enabled)
SELECT '', '智谱 BigModel', 'openai-compatible', 'https://open.bigmodel.cn/api/paas/v4', NULL, NULL, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM model_provider
    WHERE owner_id = '' AND protocol = 'openai-compatible'
      AND base_url = 'https://open.bigmodel.cn/api/paas/v4'
);

INSERT INTO model_provider_model (provider_id, model_id, display_name, capabilities, enabled)
SELECT p.id, m.model_id, m.display_name, m.capabilities::jsonb, TRUE
FROM model_provider p
JOIN (VALUES
    ('glm-4.7-flash', 'GLM-4.7-Flash（免费）', '["tools"]'),
    ('glm-4.6v-flash', 'GLM-4.6V-Flash（免费）', '["vision","tools"]')
) AS m(model_id, display_name, capabilities)
  ON p.owner_id = '' AND p.protocol = 'openai-compatible'
 AND p.base_url = 'https://open.bigmodel.cn/api/paas/v4'
ON CONFLICT (provider_id, model_id) DO NOTHING;

UPDATE model_catalog_state
SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1;
