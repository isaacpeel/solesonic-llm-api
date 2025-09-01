-- Drop the atlassian_access_token table as tokens are now stored in AWS Secrets Manager
DROP TABLE IF EXISTS atlassian_access_token;