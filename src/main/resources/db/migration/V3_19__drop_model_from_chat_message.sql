-- The model column held the user's configured preference at save time — what was asked for, not
-- what actually ran, and stamped even onto user and system rows that never involved a model. Ollama
-- reports the model that answered on its terminal response, which V3_18's response_metadata now
-- carries, so that becomes the single place a message records one.
--
-- The existing values are carried across rather than dropped with the column. They are preference
-- values, not Ollama's own report, so this is the closest honest reconstruction of history — for
-- practically every turn the two agree, since the preference is what was sent to Ollama.

-- Only ASSISTANT rows: response_metadata is null by contract on USER and SYSTEM messages, and
-- writing one for them would invent metadata for turns no model ever answered.
update public.chat_message
set response_metadata = coalesce(response_metadata, '{}'::jsonb) || jsonb_build_object('model', model)
where message_type = 'ASSISTANT'
  and model is not null;

-- Rows written between V3_18 and this migration carry the superseded shape, whose keys no longer
-- map to anything on the record. Deserialization ignores them, but leaving them would mean the
-- column held two different shapes forever.
update public.chat_message
set response_metadata = response_metadata - array [
    'promptTokens',
    'completionTokens',
    'totalTokens',
    'tokensPerSecond',
    'timeToFirstTokenMillis',
    'durationMillis'
    ]
where response_metadata is not null;

-- A row left with nothing after that shed had only superseded keys and no model to inherit. An
-- empty object would deserialize to a ResponseMetadata of all nulls, which reads as "Ollama
-- reported nothing" rather than "there is no report" — null is the honest value.
update public.chat_message
set response_metadata = null
where response_metadata = '{}'::jsonb;

alter table public.chat_message
    drop column model;
