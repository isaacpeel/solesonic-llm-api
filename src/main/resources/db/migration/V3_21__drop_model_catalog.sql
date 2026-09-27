-- Drop the model catalog: the model list is a deployment fact (each llama-server
-- process serves one fixed model), so the table and the per-user preference column go.
DROP TABLE IF EXISTS ollama_model;

ALTER TABLE public.user_preferences DROP COLUMN model;
