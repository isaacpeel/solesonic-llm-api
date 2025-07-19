create table public.ollama_model
(
    id        uuid    not null primary key default gen_random_uuid(),
    censored  boolean not null,
    created   timestamp(6) with time zone,
    embedding boolean not null,
    name      varchar(255) constraint ollama_model_name unique,
    tools     boolean not null,
    updated   timestamp(6) with time zone,
    vision    boolean not null
);

alter table public.ollama_model owner to "solesonic-llm-api";

INSERT INTO public.ollama_model (censored,
                                 created,
                                 embedding,
                                 name,
                                 tools,
                                 updated,
                                 vision)
VALUES
(true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'qwen2.5:32b', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false),
(true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'qwen2.5:7b', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false);