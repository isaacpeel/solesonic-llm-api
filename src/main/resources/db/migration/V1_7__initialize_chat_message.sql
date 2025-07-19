create table public.chat_message
(
    id              uuid not null primary key,
    chat_id         uuid,
    message         text,
    message_type    varchar(255)
        constraint chat_message_message_type_check
            check ((message_type)::text = ANY
                   ((ARRAY ['USER'::character varying,
                       'ASSISTANT'::character varying,
                       'SYSTEM'::character varying,
                       'TOOL'::character varying])::text[])
                ),
    timestamp    timestamp(6) with time zone,
    model        varchar(255)
);

alter table public.chat_message owner to "solesonic-llm-api";