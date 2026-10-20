-- One row per browser push subscription. The endpoint is a bearer capability for that browser: it is
-- never logged and never returned. It is unique because one browser subscription belongs to whichever
-- user is signed in on it last. No foreign key to a user table, consistent with the rest of the schema.
create table public.push_subscription
(
    id              uuid                        not null primary key,
    user_id         uuid                        not null,
    endpoint        varchar(2048)               not null,
    p256dh          varchar(128)                not null,
    auth            varchar(64)                 not null,
    expiration_time timestamp(6) with time zone,
    label           varchar(128)                not null,
    user_agent      varchar(256),
    created         timestamp(6) with time zone not null,
    updated         timestamp(6) with time zone not null,
    last_delivered  timestamp(6) with time zone,
    failure_count   int                         not null,
    constraint push_subscription_endpoint_unique unique (endpoint)
);

create index push_subscription_user_idx on public.push_subscription (user_id);

-- Kept apart from user_preferences: that endpoint is a full replace, and a client that did not send
-- these fields would switch notifications off.
create table public.push_preference
(
    user_id       uuid                        not null primary key,
    turn_complete boolean                     not null,
    elicitation   boolean                     not null,
    updated       timestamp(6) with time zone not null
);

alter table public.push_subscription owner to "${DB_OWNER}";
alter table public.push_preference owner to "${DB_OWNER}";
