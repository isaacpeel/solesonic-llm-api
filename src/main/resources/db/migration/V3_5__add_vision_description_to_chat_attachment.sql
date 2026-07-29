-- A null vision_description means "not described yet", which is what makes the work
-- retryable and idempotent. vision_model records which model produced the text, so a
-- bulk re-describe after changing models is:
--   update public.chat_attachment set vision_description = null where vision_model = '...';
alter table public.chat_attachment
    add column vision_description text,
    add column vision_model       varchar(255);
