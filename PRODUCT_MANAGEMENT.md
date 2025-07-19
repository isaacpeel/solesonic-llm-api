# Product Management Features

This document describes the product management features of the Solesonic LLM API, focusing on its integration with Atlassian products (Jira and Confluence) and how it leverages Retrieval Augmented Generation (RAG) to enhance product management workflows.

## Jira Integration

The Solesonic LLM API integrates with Jira to provide powerful user story management capabilities directly from the chat interface.

### User Story Creation and Management

The application allows you to create and manage user stories in Jira without leaving the chat interface:

1. **Creating User Stories**: You can create new user stories by describing what you need in natural language. The LLM will structure this into a proper user story format with:
   - Summary
   - Description
   - Acceptance criteria
   - Story points
   - Priority

2. **Assigning User Stories**: User stories can be assigned to team members directly from the chat interface:
   - The system can search for users in your Jira instance
   - You can assign stories to specific users based on their expertise or workload
   - The assignment is reflected immediately in Jira

3. **User Story Templates**: The system can generate user stories following best practices and consistent formats, ensuring standardization across your product backlog.

### Example Workflow

1. A product manager describes a feature need in the chat
2. The LLM, enhanced with RAG context from your Confluence documentation, structures this into a proper user story
3. The user story is created in Jira with appropriate fields
4. The story can be assigned to a team member
5. The Jira issue link is returned to the chat for easy reference

## Confluence Integration

The application maintains an up-to-date knowledge base by regularly scanning your Confluence spaces and pages.

### Automatic Document Scanning

1. **Scheduled Scanning**: The system automatically scans your Confluence spaces at regular intervals to detect new or updated content.

2. **Version Tracking**: The system tracks document versions to ensure only updated content is processed, optimizing resource usage.

3. **Content Processing**: Confluence pages are processed and converted into a format suitable for the RAG system:
   - HTML content is extracted and cleaned
   - Content is chunked appropriately for vector embedding
   - Metadata is preserved for context

### RAG Knowledge Base Maintenance

1. **Vector Embeddings**: Processed Confluence content is converted into vector embeddings and stored in the vector database (using pgvector).

2. **Contextual Retrieval**: When interacting with the LLM, relevant Confluence content is retrieved based on the query context.

3. **Up-to-date Information**: As your Confluence documentation evolves, the RAG system automatically incorporates the latest information, ensuring responses are based on current organizational knowledge.

## Enhancing User Story Creation with RAG

The integration of Jira and Confluence through RAG creates a powerful workflow for user story creation that leverages your organization's collective knowledge.

### How RAG Supplements User Story Creation

1. **Contextual Understanding**: When discussing new features, the LLM draws on your Confluence documentation to understand:
   - Existing product features and limitations
   - Technical architecture and constraints
   - Business goals and priorities
   - User personas and journeys

2. **Consistent Terminology**: The LLM uses the same terminology and concepts found in your documentation, ensuring alignment with your organization's language.

3. **Requirements Enrichment**: User stories can be automatically enriched with relevant details from your documentation:
   - Technical considerations from architecture documents
   - User needs from persona descriptions
   - Business context from strategy documents
   - Integration points from system documentation

4. **Gap Identification**: By comparing new requirements against existing documentation, the system can identify potential gaps or conflicts.

### Benefits

1. **Knowledge Continuity**: New team members benefit from the collective knowledge embedded in your documentation when creating user stories.

2. **Consistency**: User stories maintain consistency with your product vision and technical direction.

3. **Efficiency**: Reduces the need to manually reference multiple documents when creating user stories.

4. **Quality**: User stories are more comprehensive and better aligned with existing systems and business goals.

## Getting Started

To use these product management features:

1. Configure your Jira and Confluence integration in the `.env` file (see main README)
2. Ensure the `CONFLUENCE_TRAINING_ENABLED` environment variable is set to `true` to enable Confluence scanning
3. Use the chat interface to create and manage user stories
4. Reference your Confluence documentation in conversations to leverage the RAG capabilities

For more detailed information on the API and configuration, refer to the main [README](README.md).