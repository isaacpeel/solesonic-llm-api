alter table ollama_model
    drop column embedding;

alter table ollama_model
    drop column tools;

alter table ollama_model
    drop column vision;