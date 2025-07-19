ALTER TABLE public.atlassian_access_token
ADD COLUMN administrator BOOLEAN DEFAULT false NOT NULL;