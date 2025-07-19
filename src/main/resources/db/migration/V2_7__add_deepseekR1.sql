INSERT INTO public.ollama_model (censored,
                                 created,
                                 embedding,
                                 name,
                                 tools,
                                 updated,
                                 vision)
VALUES
    (true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false, 'hf.co/bartowski/DeepSeek-R1-Distill-Qwen-32B-GGUF', true, CURRENT_TIMESTAMP AT TIME ZONE 'UTC', false);