-- 工作流定义版本：画布编辑的图结构（节点+连线+节点参数）按版本持久化。
-- 运行绑定 workflow_key 的最新版本；内置代码定义作为首个版本的种子。

CREATE TABLE workflow_definition_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT '',
    workflow_key VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(256) NOT NULL DEFAULT '',
    graph JSONB NOT NULL,
    created_by VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workflow_definition_version UNIQUE (tenant_id, workflow_key, version),
    CONSTRAINT ck_workflow_def_version CHECK (version > 0)
);

COMMENT ON TABLE workflow_definition_version IS '工作流定义版本：graph 为 {nodes:[{key,type,name,params}],edges:[{from,to}]}；节点类型 search_image/image_understand/llm_text/web_search/poster_render';
COMMENT ON COLUMN workflow_definition_version.graph IS '节点 params 支持 {{节点key.output}} 形式引用上游输出；edges 表达执行依赖（DAG）';

CREATE INDEX idx_workflow_def_version_key ON workflow_definition_version (tenant_id, workflow_key, version DESC);

-- 种子：商品海报生成 v1（与内置定义等价的图结构）
INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, created_by)
VALUES ('default', 'product-poster', 1, '商品海报生成', '搜集商品图，理解商品卖点，自动生成一张宣传海报。',
'{
  "nodes": [
    {"key": "search_image",     "type": "search_image",      "name": "搜集商品图", "params": {"keyword": "{{input.keyword}}"}},
    {"key": "understand",       "type": "image_understand",  "name": "商品理解",   "params": {"image": "{{search_image.output}}", "question": "这是什么商品？给出商品名称、品类、三个核心卖点、适合海报的配色建议（十六进制色值）。"}},
    {"key": "compose_copy",     "type": "llm_text",          "name": "文案规划",   "params": {"prompt": "你是海报文案策划。根据商品信息生成一张宣传海报的文案，只输出 JSON：{\"title\":\"主标题(不超过12字)\",\"subtitle\":\"副标题(不超过20字)\",\"points\":[\"卖点1\",\"卖点2\",\"卖点3\"],\"palette\":{\"bgFrom\":\"#十六进制\",\"bgTo\":\"#十六进制\",\"accent\":\"#十六进制\",\"text\":\"#十六进制\"}}。商品关键词：{{input.keyword}}。商品信息：{{understand.output}}"}},
    {"key": "render_poster",    "type": "poster_render",     "name": "海报合成",   "params": {"image": "{{search_image.output}}", "copy": "{{compose_copy.output}}"}}
  ],
  "edges": [
    {"from": "search_image",  "to": "understand"},
    {"from": "understand",    "to": "compose_copy"},
    {"from": "compose_copy",  "to": "render_poster"},
    {"from": "search_image",  "to": "render_poster"}
  ]
}'::jsonb, 'system')
ON CONFLICT (tenant_id, workflow_key, version) DO NOTHING;
