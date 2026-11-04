ALTER TABLE agent_tool_call
    ADD COLUMN worker_key VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE agent_tool_call
    DROP CONSTRAINT IF EXISTS agent_tool_call_run_id_tool_name_arguments_hash_key;

ALTER TABLE agent_tool_call
    ADD CONSTRAINT uq_agent_tool_call_worker_arguments
    UNIQUE (run_id, worker_key, tool_name, arguments_hash);
