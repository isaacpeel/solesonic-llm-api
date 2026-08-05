-- Records why an image was never described, so a reloaded conversation can still explain
-- itself rather than silently showing an image the assistant never saw. Mutually exclusive
-- with vision_description: a successful describe clears this column, a failure clears the
-- description. Both null means "not attempted yet", which stays retryable.
alter table public.chat_attachment
    add column vision_failure_reason varchar(64);
