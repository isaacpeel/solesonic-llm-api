package com.solesonic.service.interest;

import com.solesonic.config.interest.InterestSettings;
import com.solesonic.model.interest.InterestAgent;
import com.solesonic.model.interest.NewsSummary;
import com.solesonic.model.push.PushEvent;
import com.solesonic.repository.interest.InterestAgentItemRepository;
import com.solesonic.service.interest.NewsSearchClient.SearchResult;
import com.solesonic.service.push.PushNotificationService;
import com.solesonic.service.user.UserPreferencesService;
import com.solesonic.util.PromptQuoting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.solesonic.config.openai.MemoryOpenAiConfig.MEMORY_CHAT_MODEL;

/**
 * Runs due news agents: search, drop what each agent has already seen, read the best of what is new,
 * summarise it, and deliver it as a new chat with a push.
 * <p>
 * The owner of everything a run writes comes from the claimed agent row and nowhere else. Web content
 * is untrusted data: it reaches the model only as quoted text with no tools offered, and the user only
 * as one {@code ASSISTANT} message whose every link was built from a search result. A run with nothing
 * new makes no model call, creates no chat and sends no push. Off the request path, sequential, one
 * model call at a time.
 */
@Service
public class NewsAgentService {
    private static final Logger log = LoggerFactory.getLogger(NewsAgentService.class);

    private final NewsRunRecorder newsRunRecorder;
    private final NewsSearchClient newsSearchClient;
    private final NewsSummaryValidator newsSummaryValidator;
    private final InterestAgentItemRepository interestAgentItemRepository;
    private final PushNotificationService pushNotificationService;
    private final UserPreferencesService userPreferencesService;
    private final InterestSettings interestSettings;
    private final Clock clock;
    private final ChatClient memoryChatClient;
    private final String summaryPrompt;

    public NewsAgentService(NewsRunRecorder newsRunRecorder,
                            NewsSearchClient newsSearchClient,
                            NewsSummaryValidator newsSummaryValidator,
                            InterestAgentItemRepository interestAgentItemRepository,
                            PushNotificationService pushNotificationService,
                            UserPreferencesService userPreferencesService,
                            InterestSettings interestSettings,
                            Clock clock,
                            @Qualifier(MEMORY_CHAT_MODEL) ChatModel memoryChatModel,
                            @Value("classpath:prompts/news-summary-prompt.st") Resource summaryPromptResource) {
        this.newsRunRecorder = newsRunRecorder;
        this.newsSearchClient = newsSearchClient;
        this.newsSummaryValidator = newsSummaryValidator;
        this.interestAgentItemRepository = interestAgentItemRepository;
        this.pushNotificationService = pushNotificationService;
        this.userPreferencesService = userPreferencesService;
        this.interestSettings = interestSettings;
        this.clock = clock;
        this.memoryChatClient = ChatClient.create(memoryChatModel);
        this.summaryPrompt = read(summaryPromptResource);
    }

    /**
     * Runs a batch of due agents, then prunes seen items past their retention.
     */
    public NewsRun runDue() {
        List<InterestAgent> claimed = newsRunRecorder.claim(ZonedDateTime.now(clock));
        NewsRun run = new NewsRun();

        for (InterestAgent agent : claimed) {
            Optional<InterestAgent> started = newsRunRecorder.start(agent, ZonedDateTime.now(clock));

            if (started.isEmpty()) {
                run.discarded();
                continue;
            }

            run(started.get(), run);
        }

        if (!claimed.isEmpty()) {
            log.info("News run over {} agent(s): {}", claimed.size(), run);
        }

        try {
            int pruned = newsRunRecorder.pruneItems(ZonedDateTime.now(clock));

            if (pruned > 0) {
                log.info("Pruned {} seen news item(s) past retention", pruned);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not prune seen news items ({})", exception.getClass().getSimpleName());
        }

        return run;
    }

    private void run(InterestAgent agent, NewsRun run) {
        try {
            Map<String, Candidate> fresh = fresh(agent, newsSearchClient.search(agent.getQuery(), interestSettings.maxResults(),
                    NewsRunSchedule.timeRange(agent.getCadence(), agent.getLastRunAt(), ZonedDateTime.now(clock))));

            if (fresh.isEmpty()) {
                newsRunRecorder.quiet(agent, List.of(), ZonedDateTime.now(clock));
                run.quiet();
                return;
            }

            LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(userPreferencesService.getZone(agent.getUserId())).toLocalDate();
            List<NewsItem> items = budgeted(items(fresh));
            Map<Integer, NewsItem> itemsByNumber = items.stream().collect(Collectors.toMap(NewsItem::number, item -> item));

            NewsSummary newsSummary = memoryChatClient.prompt()
                    .system(summaryPrompt)
                    .user(render(agent.getLabel(), today, items))
                    .call()
                    .entity(NewsSummary.class);

            Optional<ValidatedSummary> validated = newsSummaryValidator.validate(newsSummary, items.size());

            //Only what the model was shown counts as seen: a result the budget cut is offered again next run.
            Set<String> urlHashes = items.stream().map(item -> NewsUrlNormalizer.hash(item.url())).collect(Collectors.toSet());

            if (validated.isEmpty()) {
                newsRunRecorder.quiet(agent, urlHashes, ZonedDateTime.now(clock));
                run.quiet();
                return;
            }

            UUID updateId = UUID.randomUUID();
            String text = NewsUpdateRenderer.render(agent.getLabel(), today, validated.get(), itemsByNumber);

            Optional<UUID> chatId = newsRunRecorder.deliver(agent, urlHashes, updateId, NewsUpdateRenderer.chatName(agent.getLabel(), today),
                    text, validated.get().sources().size(), ZonedDateTime.now(clock));

            if (chatId.isEmpty()) {
                run.discarded();
                return;
            }

            run.delivered();
            pushNotificationService.push(agent.getUserId(), new PushEvent.InterestUpdated(chatId.get(), agent.getId(), updateId));
        } catch (RuntimeException exception) {
            log.warn("News run failed for agent {} ({})", agent.getId(), exception.getClass().getSimpleName());
            log.debug("News run failure for agent {}", agent.getId(), exception);

            run.failed();

            try {
                newsRunRecorder.failed(agent, ZonedDateTime.now(clock));
            } catch (RuntimeException recordException) {
                log.warn("Could not record the failure of agent {} ({})", agent.getId(), recordException.getClass().getSimpleName());
            }
        }
    }

    /**
     * The results this agent has not seen, by URL hash, best first. A result with no usable web URL
     * is dropped, and two spellings of one URL count once.
     */
    private Map<String, Candidate> fresh(InterestAgent agent, List<SearchResult> results) {
        Map<String, Candidate> byHash = new LinkedHashMap<>();

        results.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((SearchResult result) -> Objects.requireNonNullElse(result.relevanceScore(), 0.0)).reversed())
                .forEach(result -> NewsUrlNormalizer.normalize(result.url())
                        .ifPresent(url -> byHash.putIfAbsent(NewsUrlNormalizer.hash(url), new Candidate(url, result))));

        if (byHash.isEmpty()) {
            return byHash;
        }

        List<String> seen = interestAgentItemRepository.findSeen(agent.getId(), agent.getUserId(), byHash.keySet());
        seen.forEach(byHash::remove);

        return byHash;
    }

    /**
     * Numbered in rank order. The top {@code extract-top} are read in full in one extraction call; a
     * failed or empty extraction falls back to that result's snippet.
     */
    private List<NewsItem> items(Map<String, Candidate> fresh) {
        List<Candidate> candidates = List.copyOf(fresh.values());
        List<String> toExtract = candidates.stream().limit(interestSettings.extractTop()).map(Candidate::url).toList();

        Map<String, String> extracted = new HashMap<>();

        newsSearchClient.extract(toExtract).forEach((url, content) ->
                NewsUrlNormalizer.normalize(url).ifPresent(normalized -> extracted.put(normalized, content)));

        List<NewsItem> items = new ArrayList<>();

        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);

            items.add(new NewsItem(index + 1,
                    Objects.requireNonNullElse(candidate.result().title(), ""),
                    candidate.url(),
                    NewsUrlNormalizer.host(candidate.url()),
                    cap(Objects.requireNonNullElse(candidate.result().content(), ""), interestSettings.maxArticleCharacters()),
                    cap(extracted.get(candidate.url()), interestSettings.maxArticleCharacters()),
                    Objects.requireNonNullElse(candidate.result().relevanceScore(), 0.0)));
        }

        return items;
    }

    /**
     * Keeps the model's input within {@code max-source-characters}, dropping the lowest-ranked results
     * first. The best result is always kept, its text cut to fit if it must be.
     */
    private List<NewsItem> budgeted(List<NewsItem> items) {
        List<NewsItem> kept = new ArrayList<>();
        int characters = 0;

        for (NewsItem item : items) {
            int length = line(item).length();

            if (characters + length > interestSettings.maxSourceCharacters()) {
                if (kept.isEmpty()) {
                    int room = Math.max(0, interestSettings.maxSourceCharacters() - (length - item.body().length()));
                    kept.add(new NewsItem(item.number(), item.title(), item.url(), item.host(), cap(item.snippet(), room),
                            cap(item.text(), room), item.relevanceScore()));
                }

                break;
            }

            kept.add(item);
            characters += length;
        }

        return kept;
    }

    /**
     * Every field quoted with every line break escaped, so nothing in an article can start a line that
     * looks like another source or an instruction. The raw page content is never sent.
     */
    static String render(String label, LocalDate today, List<NewsItem> items) {
        StringBuilder rendered = new StringBuilder()
                .append("Topic: ").append(PromptQuoting.quote(label)).append('\n')
                .append("Today: ").append(NewsUpdateRenderer.DATE.format(today)).append("\n\n")
                .append("Sources, most relevant first:\n");

        for (NewsItem item : items) {
            rendered.append(line(item)).append('\n');
        }

        return rendered.toString();
    }

    private static String line(NewsItem item) {
        return "[" + item.number() + "] title=" + PromptQuoting.quote(item.title())
                + " site=" + PromptQuoting.quote(item.host())
                + " text=" + PromptQuoting.quote(item.body());
    }

    private static String cap(String text, int maxCharacters) {
        if (text == null || text.length() <= maxCharacters) {
            return text;
        }

        return text.substring(0, maxCharacters);
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Candidate(String url, SearchResult result) {
    }
}
