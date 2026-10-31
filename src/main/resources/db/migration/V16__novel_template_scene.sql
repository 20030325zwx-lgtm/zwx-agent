-- 场景级模型绑定（专用覆盖通用）：小说模板的 llm_text 节点声明 scene=novel，
-- 运行走「小说创作」场景绑定（未绑定回退通用对话）。运行绑最新版本，插入 v2 即生效。

INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, inputs_json, created_by)
SELECT tenant_id, workflow_key, 2, name, description,
       jsonb_set(
         graph,
         '{nodes}',
         (
           SELECT jsonb_agg(
                    CASE
                      WHEN n->>'key' IN ('draft', 'summarize')
                      THEN jsonb_set(n, '{params,scene}', '"novel"')
                      ELSE n
                    END ORDER BY ord)
           FROM jsonb_array_elements(graph->'nodes') WITH ORDINALITY AS t(n, ord)
         )
       ),
       inputs_json, 'system'
FROM workflow_definition_version
WHERE tenant_id = 'default' AND workflow_key = 'novel-chapter' AND version = 1
ON CONFLICT (tenant_id, workflow_key, version) DO NOTHING;
