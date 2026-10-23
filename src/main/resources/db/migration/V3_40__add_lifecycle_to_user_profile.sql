-- What Keycloak last said about each user: when the account was last looked up, and when it was
-- found deleted or disabled. A removed user's memory data is purged and never seeded again.
alter table public.user_profile
    add column checked timestamptz,
    add column removed timestamptz;
