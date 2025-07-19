create table public.vector_store
(
    id        uuid default uuid_generate_v4() not null primary key,
    content   text,
    metadata  json,
    embedding vector(384)
);

alter table public.vector_store owner to "solesonic-llm-api";

create index spring_ai_vector_index on public.vector_store using hnsw (embedding public.vector_cosine_ops);