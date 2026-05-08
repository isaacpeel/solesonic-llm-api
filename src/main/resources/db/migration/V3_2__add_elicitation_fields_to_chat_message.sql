alter table public.chat_message
    add column elicitation_id uuid;

alter table public.chat_message
    add column elicitation_response jsonb;

CREATE INDEX idx_chat_message_elicitation_id
    ON chat_message (elicitation_id);
