-- AI 小说创作 v1（2026-10-31）：章节库 = 创作项目的有状态内容层。
-- 每写一章 = 一次工作流运行：novel_context 读项目设定与既往章节（目录/概要）作上下文，
-- llm_text 生成正文与概要，novel_chapter_save 写回章节库（DRAFT）；确认后概要进入后续章节上下文。

CREATE TABLE novel_chapter (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    chapter_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    plot_direction TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    summary TEXT NOT NULL DEFAULT '',
    word_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    source_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_novel_chapter_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_novel_chapter_number UNIQUE (project_id, chapter_number)
);
CREATE INDEX idx_novel_chapter_project ON novel_chapter (project_id, chapter_number);

COMMENT ON TABLE novel_chapter IS '小说章节：工作流 novel_chapter_save 节点按 source_run_id 幂等写入草稿；summary 供 novel_context 注入后续章节上下文；CONFIRMED 不可删除';

-- 项目设定（自由文本块）：长篇创作共用；小说约定 {characters, worldview, style}
ALTER TABLE creative_project ADD COLUMN IF NOT EXISTS settings_json JSONB NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN creative_project.settings_json IS '项目设定：小说约定 {characters 人物设定, worldview 世界观, style 文风}；novel_context 节点读取注入生成上下文';

-- 种子模板：小说章节创作（与前端「写新章节」默认模板对应）
INSERT INTO workflow_definition_version (tenant_id, workflow_key, version, name, description, graph, created_by)
VALUES ('default', 'novel-chapter', 1, '小说章节创作',
 '读取作品设定、既往章节目录与近几章概要，按本章剧情走向生成正文与概要，存为章节草稿。',
'{
  "nodes": [
    {"key": "context",   "type": "novel_context",     "name": "章节上下文", "params": {"summaryCount": 3}},
    {"key": "draft",     "type": "llm_text",          "name": "章节正文",
     "params": {"system": "你是资深网文作者，擅长把剧情走向扩写为画面感强、节奏紧凑的正文章节。只输出正文，不要输出任何解释或元信息。",
                "prompt": "请根据以下作品上下文创作本章正文，约2000字。\\n\\n{{context.output}}\\n\\n【本章标题】{{input.title}}\\n【本章剧情走向】{{input.plotDirection}}\\n\\n要求：衔接既往章节的剧情与伏笔，保持人物性格与世界观一致，结尾留下推进下一章的钩子。"}},
    {"key": "summarize", "type": "llm_text",          "name": "剧情概要",
     "params": {"prompt": "请为以下章节正文写一份剧情概要，100字以内，只输出概要本身，涵盖关键事件、人物行动与留下的伏笔：\\n\\n{{draft.output}}"}},
    {"key": "save",      "type": "novel_chapter_save", "name": "章节入库",
     "params": {"title": "{{input.title}}", "content": "{{draft.output}}", "summary": "{{summarize.output}}", "plotDirection": "{{input.plotDirection}}"}}
  ],
  "edges": [
    {"from": "context",   "to": "draft"},
    {"from": "draft",     "to": "summarize"},
    {"from": "summarize", "to": "save"}
  ]
}'::jsonb, 'system')
ON CONFLICT (tenant_id, workflow_key, version) DO NOTHING;
