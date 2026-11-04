package com.solesonic.rag.eval;

import com.solesonic.model.llm.LlmApiKey;
import com.solesonic.model.rag.DocumentPrincipal;
import com.solesonic.service.rag.HistoryAwareQueryCompressor;
import com.solesonic.service.rag.ScopedDocumentRetriever;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval evaluation set (plan WS7): known documents, known questions, and whether the
 * expected passage comes back — at baseline and under each new setting (follow-up compression on or
 * off, chat floor 0 or 3).
 * <p>
 * Three documents of known content, attached to one conversation: plain text read by
 * {@link TextReader}, a DOCX read by Tika and a PDF read by the page reader — the formats are
 * rendered here from the {@code rag/eval} text sources, so the binaries never need checking in.
 * A second conversation holds a near-copy of the warranty, to prove the floor never leaks across
 * conversations.
 * <p>
 * <strong>What this measures, and what it does not.</strong> Embeddings are a deterministic
 * hashed bag of words, so the run is stable in CI; the absolute numbers say nothing about the real
 * embedding model, and the similarity threshold here (0.3) is tuned to this stub, not to
 * production's 0.5. Compression is played by a scripted transformer that returns the standalone
 * question a good compressor would — so the compression column is an upper bound on what the real
 * task model can achieve. What the run does show reliably is how each setting changes <em>which
 * question types</em> retrieve their passage, and that the floor surfaces only this conversation's
 * chunks. Threshold decisions need a manual run against the real embedding model (see the plan).
 * <p>
 * Writes {@code target/rag-eval/report.md}.
 */
class ChatRetrievalEvalTest {
    private static final Logger log = LoggerFactory.getLogger(ChatRetrievalEvalTest.class);

    private static final int TOP_K = 15;
    private static final double STUB_CHAT_THRESHOLD = 0.3;
    private static final LlmApiKey LLM_API_KEY = new LlmApiKey("sk-eval-0000000001");

    private static final UUID CHAT_ID = UUID.randomUUID();
    private static final UUID OTHER_CHAT_ID = UUID.randomUUID();
    private static final String OTHER_CHAT_MARKER = "OTHER-CHAT";

    private enum Kind { SELF_CONTAINED, FOLLOW_UP, VAGUE, UNRELATED, MULTI_DOCUMENT }

    /**
     * @param previousQuestion the earlier user turn, or null for a first message
     * @param standalone       what a good compressor would turn the question into
     * @param expected         phrases that must all appear in what is retrieved; empty for an
     *                         unrelated question (where anything from the chat is an intrusion) and
     *                         for a vague one (where anything from the chat is a hit)
     */
    private record EvalCase(Kind kind, String previousQuestion, String question, String standalone, List<String> expected) {
    }

    private record Setting(String name, boolean compression, int floor) {
    }

    private static final List<EvalCase> CASES = List.of(
            new EvalCase(Kind.SELF_CONTAINED, null, "How many months does the amplifier warranty cover parts?", null,
                    List.of("twenty four months")),
            new EvalCase(Kind.SELF_CONTAINED, null, "Who approves production database access requests?", null,
                    List.of("second approval from the security team")),
            new EvalCase(Kind.SELF_CONTAINED, null, "What did version 4.2 change about the standby timeout?", null,
                    List.of("standby timeout")),
            new EvalCase(Kind.FOLLOW_UP, "Tell me about the labor coverage in the warranty.", "What happens after that?",
                    "What happens to repair labor coverage after the first twelve months?", List.of("owner pays labor")),
            new EvalCase(Kind.FOLLOW_UP, "How do I open a warranty claim?", "How long until I hear back?",
                    "How long until a return authorization number is issued for a warranty claim?", List.of("three business days")),
            new EvalCase(Kind.FOLLOW_UP, "What is the on-call rotation like?", "When do I start?",
                    "When do engineers join the on-call rotation?", List.of("sixth week")),
            new EvalCase(Kind.VAGUE, null, "Summarize this", null, List.of()),
            new EvalCase(Kind.VAGUE, null, "What does it say?", null, List.of()),
            new EvalCase(Kind.UNRELATED, null, "What is the capital of France?", null, List.of()),
            new EvalCase(Kind.UNRELATED, null, "Write me a haiku about autumn leaves", null, List.of()),
            new EvalCase(Kind.MULTI_DOCUMENT, null,
                    "What do the release notes say about the firmware crash and what does the warranty say about liquid spills?",
                    null, List.of("firmware crash", "liquid spills")));

    private static final List<Setting> SETTINGS = List.of(
            new Setting("baseline", false, 0),
            new Setting("compression", true, 0),
            new Setting("floor=3", false, 3),
            new Setting("compression+floor=3", true, 3));

    private static SimpleVectorStore vectorStore;

    @BeforeAll
    static void ingestFixtures() throws IOException {
        vectorStore = SimpleVectorStore.builder(new HashingEmbeddingModel()).build();

        List<Document> chunks = new ArrayList<>();
        chunks.addAll(chunks(new TextReader(fixture("warranty-policy.txt")), CHAT_ID, ""));
        chunks.addAll(chunks(new TikaDocumentReader(docx(fixtureText("onboarding-guide.txt"))), CHAT_ID, ""));
        chunks.addAll(chunks(new PagePdfDocumentReader(pdf(fixtureText("release-notes.txt"))), CHAT_ID, ""));
        chunks.addAll(chunks(new TextReader(fixture("warranty-policy.txt")), OTHER_CHAT_ID, OTHER_CHAT_MARKER + " "));

        vectorStore.add(chunks);
    }

    @Test
    void evaluatesEverySettingAndWritesTheReport() throws IOException {
        Map<Setting, Map<Kind, int[]>> scores = new LinkedHashMap<>();
        StringBuilder detail = new StringBuilder();

        for (Setting setting : SETTINGS) {
            Map<Kind, int[]> byKind = new LinkedHashMap<>();

            for (EvalCase evalCase : CASES) {
                List<Document> retrieved = retrieve(setting, evalCase);

                assertThat(retrieved).as("%s / %s leaked another conversation's chunk", setting.name(), evalCase.question())
                        .noneMatch(document -> document.getText().contains(OTHER_CHAT_MARKER));

                boolean hit = hit(evalCase, retrieved);

                int[] score = byKind.computeIfAbsent(evalCase.kind(), _ -> new int[2]);
                score[0] += hit ? 1 : 0;
                score[1]++;

                detail.append("| %s | %s | %s | %d | %s |%n".formatted(setting.name(), evalCase.kind(),
                        evalCase.question(), retrieved.size(), hit ? "yes" : "no"));
            }

            scores.put(setting, byKind);
        }

        writeReport(scores, detail);

        Map<Kind, int[]> baseline = scores.get(SETTINGS.get(0));
        Map<Kind, int[]> compression = scores.get(SETTINGS.get(1));
        Map<Kind, int[]> floor = scores.get(SETTINGS.get(2));

        // The harness itself is sound: a self-contained question finds its passage at baseline.
        assertThat(baseline.get(Kind.SELF_CONTAINED)[0]).isEqualTo(baseline.get(Kind.SELF_CONTAINED)[1]);

        // Each setting does what it is for, and never makes another question type worse.
        assertThat(compression.get(Kind.FOLLOW_UP)[0]).isGreaterThan(baseline.get(Kind.FOLLOW_UP)[0]);
        assertThat(floor.get(Kind.VAGUE)[0]).isGreaterThan(baseline.get(Kind.VAGUE)[0]);

        for (Kind kind : List.of(Kind.SELF_CONTAINED, Kind.MULTI_DOCUMENT)) {
            assertThat(compression.get(kind)[0]).as("compression on %s", kind).isGreaterThanOrEqualTo(baseline.get(kind)[0]);
            assertThat(floor.get(kind)[0]).as("floor on %s", kind).isGreaterThanOrEqualTo(baseline.get(kind)[0]);
        }

        // Without the floor, an unrelated question surfaces nothing from the conversation. With it,
        // it does (UNRELATED intrusions in the report) — the cost the "use only if relevant" prompt
        // exists to absorb, and why the floor ships off.
        assertThat(baseline.get(Kind.UNRELATED)[0]).isZero();

        // Recorded, not asserted: a compound question spanning two documents embeds as one diluted
        // vector and retrieves neither passage under any setting here. Query expansion (deferred) is
        // what would address it.
    }

    private List<Document> retrieve(Setting setting, EvalCase evalCase) {
        List<Message> history = new ArrayList<>();

        if (evalCase.previousQuestion() != null) {
            history.add(new UserMessage(evalCase.previousQuestion()));
            history.add(new AssistantMessage("(earlier answer)"));
        }

        history.add(new UserMessage(evalCase.question()));

        Query query = Query.builder().text(evalCase.question()).history(history).build();

        QueryTransformer scriptedCompressor = original -> original.mutate()
                .text(evalCase.standalone() != null ? evalCase.standalone() : original.text())
                .build();

        query = new HistoryAwareQueryCompressor(setting.compression(), true, () -> scriptedCompressor).transform(query);

        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        DocumentPrincipal chat = DocumentPrincipal.chat(CHAT_ID);

        ScopedDocumentRetriever.ScopedTier chatTier = new ScopedDocumentRetriever.ScopedTier(chat,
                filterExpressionBuilder.eq("entitlements", chat.key()).build(), STUB_CHAT_THRESHOLD, setting.floor());

        return new ScopedDocumentRetriever(vectorStore, TOP_K, List.of(chatTier), LLM_API_KEY).retrieve(query);
    }

    /**
     * Unrelated: a "hit" counts an intrusion — any chunk of the conversation surfacing at all.
     * Vague: any chunk of the conversation is a hit.
     */
    private static boolean hit(EvalCase evalCase, List<Document> retrieved) {
        if (evalCase.expected().isEmpty()) {
            return !retrieved.isEmpty();
        }

        String all = retrieved.stream().map(Document::getText).reduce("", (left, right) -> left + "\n" + right);

        return evalCase.expected().stream().allMatch(all::contains);
    }

    private static void writeReport(Map<Setting, Map<Kind, int[]>> scores, StringBuilder detail) throws IOException {
        StringBuilder report = new StringBuilder("""
                # Chat retrieval evaluation

                Stub embedding (hashed bag of words), chat threshold %.2f, top-k %d. Compression is scripted
                (an upper bound on a real task model). For UNRELATED the number is intrusions: lower is better.

                | setting | %s |
                |---|%s
                """.formatted(STUB_CHAT_THRESHOLD, TOP_K,
                String.join(" | ", Arrays.stream(Kind.values()).map(Enum::name).toList()),
                "---|".repeat(Kind.values().length)));

        scores.forEach((setting, byKind) -> {
            report.append("| ").append(setting.name());

            for (Kind kind : Kind.values()) {
                int[] score = byKind.get(kind);
                report.append(" | ").append(score[0]).append("/").append(score[1]);
            }

            report.append(" |\n");
        });

        report.append("\n## Per question\n\n| setting | kind | question | retrieved | hit |\n|---|---|---|---|---|\n")
                .append(detail);

        Path path = Path.of("target", "rag-eval", "report.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, report.toString());

        log.info("Chat retrieval evaluation written to {}\n{}", path.toAbsolutePath(), report);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static Resource fixture(String name) {
        return new ClassPathResource("rag/eval/" + name);
    }

    private static String fixtureText(String name) throws IOException {
        return fixture(name).getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * One chunk per paragraph (or per line, where a reader drops blank lines) — a stand-in for the
     * production splitter that keeps every expected passage in exactly one chunk.
     */
    private static List<Document> chunks(DocumentReader documentReader, UUID chatId, String prefix) {
        List<Document> chunks = new ArrayList<>();

        for (Document document : documentReader.read()) {
            for (String paragraph : document.getText().split("\\R")) {
                String text = paragraph.strip();

                if (text.length() < 20) {
                    continue;
                }

                chunks.add(Document.builder()
                        .text(prefix + text)
                        .metadata(Map.of("entitlements", DocumentPrincipal.chat(chatId).key()))
                        .build());
            }
        }

        return chunks;
    }

    private static Resource pdf(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                content.setLeading(14);
                content.beginText();
                content.newLineAtOffset(40, 740);

                for (String line : text.split("\\R")) {
                    if (!line.isBlank()) {
                        content.showText(line.strip());
                        content.newLine();
                    }
                }

                content.endText();
            }

            document.save(out);

            return named(out.toByteArray(), "release-notes.pdf");
        }
    }

    /**
     * The smallest valid DOCX: a content-types part, a package relationship and the document body,
     * one {@code w:p} per paragraph. Built by hand because POI's lite schemas cannot write one.
     */
    private static Resource docx(String text) throws IOException {
        StringBuilder body = new StringBuilder();

        for (String paragraph : text.split("\\R\\R")) {
            if (!paragraph.isBlank()) {
                body.append("<w:p><w:r><w:t>").append(xml(paragraph.strip())).append("</w:t></w:r></w:p>");
            }
        }

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""");
        parts.put("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""");
        parts.put("word/document.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>%s</w:body></w:document>"""
                .formatted(body));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            zip.finish();

            return named(out.toByteArray(), "onboarding-guide.docx");
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Resource named(byte[] bytes, String fileName) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    /**
     * Deterministic embeddings: lower-cased words minus stop words, crudely singularised, hashed into
     * 1024 buckets and L2-normalised. Cosine similarity is then word overlap — enough to tell a
     * question that names its subject from one that does not, which is what the settings change.
     * A small constant bucket keeps a question made only of stop words from embedding to zero, which
     * a real model never does either.
     */
    static final class HashingEmbeddingModel implements EmbeddingModel {
        private static final int DIMENSIONS = 1024;
        private static final float BIAS = 0.1f;

        private static final Set<String> STOP_WORDS = Set.of(
                "a", "an", "the", "of", "to", "and", "or", "is", "are", "was", "for", "in", "on", "at", "by",
                "with", "what", "how", "does", "do", "did", "i", "me", "my", "it", "this", "that", "about",
                "from", "be", "after", "before", "until", "when", "who", "which", "say", "says", "tell",
                "long", "many", "happens", "hear", "back", "start", "like", "s");

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();

            for (int index = 0; index < request.getInstructions().size(); index++) {
                embeddings.add(new Embedding(vector(request.getInstructions().get(index)), index));
            }

            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private static float[] vector(String text) {
            float[] vector = new float[DIMENSIONS];
            vector[0] = BIAS;

            for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.isBlank() || STOP_WORDS.contains(token)) {
                    continue;
                }

                String stem = token.length() > 3 && token.endsWith("s") ? token.substring(0, token.length() - 1) : token;

                vector[1 + Math.floorMod(stem.hashCode(), DIMENSIONS - 1)] += 1f;
            }

            double norm = 0;

            for (float value : vector) {
                norm += value * value;
            }

            float scale = (float) (1 / Math.sqrt(norm));

            for (int index = 0; index < vector.length; index++) {
                vector[index] *= scale;
            }

            return vector;
        }
    }
}
