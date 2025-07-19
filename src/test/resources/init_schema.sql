CREATE SCHEMA IF NOT EXISTS public;

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;

CREATE ROLE "solesonic-llm-api" WITH LOGIN PASSWORD 'docker_pw';
ALTER ROLE "solesonic-llm-api" CREATEDB CREATEROLE;

CREATE DATABASE "solesonic-llm-api";
GRANT ALL PRIVILEGES ON DATABASE "solesonic-llm-api" TO "solesonic-llm-api";
GRANT ALL PRIVILEGES ON SCHEMA public TO "solesonic-llm-api";

