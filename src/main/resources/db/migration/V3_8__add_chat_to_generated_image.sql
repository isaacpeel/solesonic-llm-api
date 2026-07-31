-- An image generated inside a conversation belongs to that conversation's assistant turn, the same
-- way an attachment belongs to a user turn. Both stay null for explicit generation from /images,
-- which has no chat.
alter table public.generated_image
    add column chat_id uuid,
    add column chat_message_id uuid;

create index generated_image_chat_id_idx on public.generated_image (chat_id);

-- The claim in GeneratedImageRepository.bind matches on this: images already produced for a chat
-- but not yet attached to the assistant message that is about to be written.
create index generated_image_unbound_idx on public.generated_image (chat_id, created)
    where chat_message_id is null;
