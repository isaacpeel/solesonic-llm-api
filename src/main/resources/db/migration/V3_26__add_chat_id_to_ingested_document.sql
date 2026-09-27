-- The CHAT scope becomes a document collection of its own -- /chats/{chatId}/documents -- alongside
-- the GLOBAL and USER collections, so a conversation's retrievable material can be listed, added to,
-- refreshed and removed the same way the other two scopes' can.
--
-- A collection needs a paged listing and a scoped by-id lookup, and the listing has to run without
-- loading file_data: a page of twenty whole rows is a page of twenty uploaded files. That projection
-- is expressible only in JPQL, which cannot index into the json metadata column -- so the
-- conversation a CHAT row belongs to needs a column of its own, exactly what user_id already is for
-- the USER collection.
--
-- The metadata key stays written and stays read. IngestedDocumentRepository.findByChatId and
-- findByChatAttachmentId are the teardown queries ChatAttachmentService calls when an attachment or
-- a whole chat is deleted, and DocumentService stamps chunk metadata from the same map. This column
-- is the queryable half, not a replacement for either.

alter table public.ingested_document add column chat_id uuid;

update public.ingested_document
   set chat_id = (metadata ->> 'CHAT_ID')::uuid
 where scope = 'CHAT'
   and metadata ->> 'CHAT_ID' is not null;

create index ingested_document_chat_id_idx on public.ingested_document (chat_id) where chat_id is not null;
