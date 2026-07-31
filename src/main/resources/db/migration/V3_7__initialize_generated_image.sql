create table public.generated_image
(
    id              uuid         not null primary key,
    user_id         uuid         not null,
    prompt          text         not null,
    model           varchar(255),
    seed            bigint,
    width           integer,
    height          integer,
    steps           integer,
    elapsed_seconds double precision,
    sha256          varchar(64)  not null,
    content_type    varchar(255) not null,
    image_data      bytea        not null,
    file_size_bytes bigint       not null,
    created         timestamp(6) with time zone not null
);

create index generated_image_user_id_idx on public.generated_image (user_id, created desc);

-- The digest is the strong ETag the download endpoint serves, so it is looked up as often as the id.
create index generated_image_sha256_idx on public.generated_image (sha256);

alter table public.generated_image owner to "${DB_OWNER}";
