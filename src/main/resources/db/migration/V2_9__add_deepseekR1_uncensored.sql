INSERT INTO public.ollama_model (censored,
                                 created,
                                 embedding,
                                 name,
                                 tools,
                                 updated,
                                 vision)
VALUES
    (false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'aia/DeepSeek-R1-Distill-Qwen-32B-Uncensored-i1:latest', false, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false);