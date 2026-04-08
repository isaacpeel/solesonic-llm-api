alter table public.chat
    add column active_commands jsonb DEFAULT '[]'::jsonb;

CREATE INDEX idx_chat_active_commands
    ON chat USING GIN (active_commands);

alter table public.chat_message
    add column commands jsonb DEFAULT '[]'::jsonb;

CREATE INDEX idx_chat_message_commands
    ON chat_message USING GIN (commands);