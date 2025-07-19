INSERT INTO public.ollama_model (censored,
                                 created,
                                 embedding,
                                 name,
                                 tools,
                                 updated,
                                 vision)
VALUES
(true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'llama3.2:1b', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false);