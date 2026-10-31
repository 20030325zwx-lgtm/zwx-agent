-- 创作闭环最小场景（Infinite Canvas 评审第一批 B1）：
-- ① 双候选：一次运行产出两张不同风格的商品图（两个独立分支，单支失败不阻断），产物自动归入项目素材库供对比；
-- ② 选定图海报：人工在素材库选定一张后发起，视觉理解 → 文案 → 海报合成（确定性渲染，零生图计费）。

INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, created_by)
VALUES ('default', 'dual-candidate-poster', 1, '商品海报·双候选',
 '同一关键词生成两张不同风格候选商品图（直白卖点 / 场景氛围），产物归入素材库，供对比选定。',
'{
  "nodes": [
    {"key": "start",  "type": "start",               "name": "开始",     "params": {}},
    {"key": "cand_a", "type": "media_image_generate", "name": "候选A·卖点直击",
     "params": {"prompt": "为「{{input.keyword}}」生成一张电商商品主图：纯色浅背景，商品居中占画面 70%，突出材质与做工细节，干净利落的卖点直击风格，无文字", "size": "1024*1024"}},
    {"key": "cand_b", "type": "media_image_generate", "name": "候选B·场景氛围",
     "params": {"prompt": "为「{{input.keyword}}」生成一张场景氛围商品图：自然光，生活方式场景（家居/桌面/户外任选最贴合的），商品清晰且是视觉焦点，柔和景深，无文字", "size": "1024*1024"}}
  ],
  "edges": [
    {"from": "start",  "to": "cand_a"},
    {"from": "start",  "to": "cand_b"}
  ]
}'::jsonb, 'system'),
('default', 'selected-poster', 1, '选定商品图海报',
 '把选定的一张商品图加工为海报：视觉理解提炼卖点 → 生成文案 JSON → 确定性合成海报。运行输入：imageAssetId（选定素材）与 keyword（卖点方向）。',
'{
  "nodes": [
    {"key": "start",      "type": "start",             "name": "开始",     "params": {}},
    {"key": "understand", "type": "image_understand",   "name": "商品理解",
     "params": {"imageAssetId": "{{input.imageAssetId}}", "question": "这是什么商品？给出商品名称、品类、三个核心卖点、适合海报的配色建议（十六进制色值）。"}},
    {"key": "copy",       "type": "llm_text",          "name": "文案策划",
     "params": {"prompt": "你是海报文案策划。根据商品信息生成海报文案，只输出 JSON：{\"title\":\"主标题(不超过12字)\",\"subtitle\":\"副标题(不超过20字)\",\"points\":[\"卖点1\",\"卖点2\",\"卖点3\"],\"palette\":{\"bgFrom\":\"#十六进制\",\"bgTo\":\"#十六进制\",\"accent\":\"#十六进制\",\"text\":\"#十六进制\"}}。卖点方向：{{input.keyword}}。商品信息：{{understand.output}}"}},
    {"key": "poster",     "type": "poster_render",      "name": "海报合成",
     "params": {"imageAssetId": "{{input.imageAssetId}}", "copy": "{{copy.output}}"}}
  ],
  "edges": [
    {"from": "start",      "to": "understand"},
    {"from": "understand", "to": "copy"},
    {"from": "copy",       "to": "poster"}
  ]
}'::jsonb, 'system')
ON CONFLICT (tenant_id, workflow_key, version) DO NOTHING;
