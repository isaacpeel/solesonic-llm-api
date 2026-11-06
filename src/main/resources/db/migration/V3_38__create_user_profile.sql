-- The name the identity provider gives each user, recorded from their token so work that runs with
-- no request bound — memory reflection — can tell the user apart from the people they mention.
create table public.user_profile
(
    user_id      uuid                        not null primary key,
    display_name varchar(128)                not null,
    updated      timestamp(6) with time zone not null
);

alter table public.user_profile owner to "${DB_OWNER}";
