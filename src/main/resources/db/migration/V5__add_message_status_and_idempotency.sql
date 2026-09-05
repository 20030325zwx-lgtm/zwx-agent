ALTER TABLE love_chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE love_chat_message ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_love_chat_message_client_request
    ON love_chat_message (client_request_id)
    WHERE client_request_id IS NOT NULL;

ALTER TABLE agent_chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE agent_chat_message ADD COLUMN IF NOT EXISTS client_request_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_chat_message_client_request
    ON agent_chat_message (client_request_id)
    WHERE client_request_id IS NOT NULL;
