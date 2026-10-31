-- 运行输入声明（评审 R03 完整版 + Infinite Canvas 批次二）：
-- 工作流定义声明 inputs 字段（key/label/type/required/placeholder），运行入口按声明渲染业务化表单，
-- 运行者不再接触 {{input.keyword}} 模板语法；未声明的旧模板由前端扫描推导兜底。

ALTER TABLE workflow_definition_version ADD COLUMN IF NOT EXISTS inputs_json JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN workflow_definition_version.inputs_json IS
    '运行输入声明：[{key,label,type:text|asset|assets,required,placeholder}]；asset 渲染素材选择控件';

-- 既有种子模板补声明（业务化字段）
UPDATE workflow_definition_version SET inputs_json = '[
  {"key":"keyword","label":"商品关键词","type":"text","required":true,"placeholder":"如：加绒保暖 不起球 显瘦百搭"}
]'::jsonb
WHERE tenant_id = 'default' AND workflow_key IN ('dual-candidate-poster', 'product-poster');

UPDATE workflow_definition_version SET inputs_json = '[
  {"key":"imageAssetId","label":"选定商品图","type":"asset","required":true,"placeholder":"从素材库选定一张商品图"},
  {"key":"keyword","label":"卖点方向","type":"text","required":false,"placeholder":"可留空，由商品理解自动提炼"}
]'::jsonb
WHERE tenant_id = 'default' AND workflow_key = 'selected-poster';

UPDATE workflow_definition_version SET inputs_json = '[
  {"key":"plotDirection","label":"本章剧情走向","type":"text","required":true,"placeholder":"用一两句话描述本章要发生什么"},
  {"key":"title","label":"章节标题","type":"text","required":false,"placeholder":"可留空，自动按「第N章」命名"}
]'::jsonb
WHERE tenant_id = 'default' AND workflow_key = 'novel-chapter';

UPDATE workflow_definition_version SET inputs_json = '[
  {"key":"keyword","label":"商品关键词","type":"text","required":true,"placeholder":"如：加绒保暖 不起球 显瘦百搭"},
  {"key":"modelAssetId","label":"模特照片","type":"asset","required":true,"placeholder":"选择一张模特图"},
  {"key":"topAssetId","label":"上衣商品图","type":"asset","required":true,"placeholder":"选择上衣商品图"},
  {"key":"pantsAssetId","label":"裤装商品图","type":"asset","required":true,"placeholder":"选择裤装商品图"}
]'::jsonb
WHERE tenant_id = 'default' AND workflow_key = 'taobao-apparel-poster';
