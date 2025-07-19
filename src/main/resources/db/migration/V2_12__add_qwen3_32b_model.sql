INSERT INTO public.ollama_model (censored,
                                 created,
                                 embedding,
                                 name,
                                 tools,
                                 updated,
                                 vision)
VALUES
    (true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'qwen3:32b', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false);