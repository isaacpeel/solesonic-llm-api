-- Chat now runs against an OpenAI-compatible server, which reports token usage rather than Ollama's
-- eval counts and nanosecond durations. V3_18's shape was Ollama's, so the keys are renamed in place
-- to what the code now reads. Without this, existing rows would still parse -- the mapper ignores
-- unknown properties -- but every number on them would silently read back as null.

alter table public.chat_message
    add column response_metadata_calls jsonb;

-- The per-call breakdown only exists for turns recorded from here on. A pre-existing row's totals are
-- all that was ever captured, so its breakdown stays null rather than being invented as a single call.

update public.chat_message
set response_metadata = (response_metadata
    - 'promptEvalCount'
    - 'evalCount'
    - 'doneReason'
    - 'promptEvalDurationNanos'
    - 'evalDurationNanos'
    -- Neither of these has an equivalent in what an OpenAI-compatible server reports: llama.cpp's
    -- timings object has no total and no model-load time, so keeping them would leave two fields that
    -- can never again be anything but null.
    - 'totalDurationNanos'
    - 'loadDurationNanos')
    || jsonb_strip_nulls(jsonb_build_object(
        'promptTokens', response_metadata -> 'promptEvalCount',
        'completionTokens', response_metadata -> 'evalCount',
        'finishReason', response_metadata -> 'doneReason',
        -- Nanoseconds to milliseconds, matching llama.cpp's own unit. A lossless unit change, not a
        -- re-measurement.
        'promptMillis', to_jsonb(trim_scale((response_metadata ->> 'promptEvalDurationNanos')::numeric / 1000000)),
        'predictedMillis', to_jsonb(trim_scale((response_metadata ->> 'evalDurationNanos')::numeric / 1000000)),
        -- There is no stored total: Ollama reported the two counts and nothing else, so it is summed
        -- here, and only where both halves are actually present.
        'totalTokens', case
            when response_metadata -> 'promptEvalCount' is not null
                and response_metadata -> 'evalCount' is not null
                then to_jsonb((response_metadata ->> 'promptEvalCount')::int
                    + (response_metadata ->> 'evalCount')::int)
            end,
        -- Every historical row is one model call: the old capture recorded a single terminal response
        -- and had no notion of a turn making more than one.
        'modelCalls', to_jsonb(1)))
where response_metadata is not null;

-- A row that held nothing but the dropped keys has nothing left but the call count stamped above, and
-- "one model call, of which nothing is known" is worse than absent -- which is what the contract says
-- a turn nothing was reported for should be.
update public.chat_message
set response_metadata = null
where response_metadata - 'modelCalls' = '{}'::jsonb;
