-- The identity provider's username and email, recorded from each user's token alongside their name,
-- so a user id can be shown as the account it belongs to. A token may carry any of the three, so
-- none of them is required.
alter table public.user_profile
    add column username varchar(255),
    add column email    varchar(320),
    alter column display_name drop not null;
