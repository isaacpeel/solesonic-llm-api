create table public.chat_attachment
(
    id              uuid not null primary key,
    user_id         uuid not null,
    chat_id         uuid,
    chat_message_id uuid,
    file_name       varchar(255) not null,
    description     text,
    content_type    varchar(255) not null,
    file_data       bytea        not null,
    file_size_bytes bigint       not null,
    created         timestamp(6) with time zone not null,
    constraint chat_attachment_bound_together
        check ((chat_id is null) = (chat_message_id is null))
);

create index chat_attachment_chat_id_idx on public.chat_attachment (chat_id);
create index chat_attachment_staged_idx on public.chat_attachment (created)
    where chat_message_id is null;

alter table public.chat_attachment owner to "${DB_OWNER}";
