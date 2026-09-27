create table public.address (
    id uuid primary key,
    address varchar(255),
    city varchar(100),
    state varchar(100),
    zip varchar(20)
);

alter table public.user_preferences
    add column address_id uuid references public.address(id) on delete set null;
