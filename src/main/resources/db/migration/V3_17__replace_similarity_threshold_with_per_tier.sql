alter table public.user_preferences
    add column chat_similarity_threshold double precision,
    add column user_similarity_threshold double precision,
    add column global_similarity_threshold double precision;

update public.user_preferences
set chat_similarity_threshold = similarity_threshold,
    user_similarity_threshold = similarity_threshold,
    global_similarity_threshold = similarity_threshold
where similarity_threshold is not null;

alter table public.user_preferences
    drop column similarity_threshold;
