-- User-supplied display name, distinct from the prompt: renaming never re-generates or re-indexes
-- anything, it only changes what a management UI shows. Nullable, since every existing row and every
-- newly generated image starts unnamed until the owner renames it.
alter table public.generated_image
    add column name varchar(255);

-- Supports the admin "every user's images" listing, which has no user_id to filter on and therefore
-- cannot use generated_image_user_id_idx for its created-desc ordering.
create index generated_image_created_idx on public.generated_image (created desc);
