-- The chat attachments an image was guided by when they were sent to the image tool as reference
-- images, in slot order. Null for a prompt-only generation, which is every existing row. Not a foreign
-- key: chat_attachment rows go with their chat, while the image they guided is kept.
alter table public.generated_image
    add column reference_attachment_ids uuid[];
