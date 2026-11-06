package com.solesonic.api.interest;

import com.solesonic.exception.interest.InterestException;
import com.solesonic.model.interest.InterestAcceptRequest;
import com.solesonic.model.interest.InterestAgentSummary;
import com.solesonic.model.interest.InterestAgentUpdateRequest;
import com.solesonic.model.interest.InterestOfferSummary;
import com.solesonic.model.interest.InterestUpdateSummary;
import com.solesonic.service.interest.InterestAgentService;
import com.solesonic.service.interest.InterestOfferService;
import com.solesonic.service.interest.InterestUpdateService;
import com.solesonic.service.security.ResourceOwnershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A user's interest offers, agents and the news chats they created. Another user's path is a
 * {@code 404}, as on the push endpoints, and every query below is scoped to the path user in its
 * {@code where} clause as well.
 */
@RestController
@RequestMapping("/users/{userId}")
public class InterestController {

    private final InterestOfferService interestOfferService;
    private final InterestAgentService interestAgentService;
    private final InterestUpdateService interestUpdateService;
    private final ResourceOwnershipService resourceOwnershipService;

    public InterestController(InterestOfferService interestOfferService,
                              InterestAgentService interestAgentService,
                              InterestUpdateService interestUpdateService,
                              ResourceOwnershipService resourceOwnershipService) {
        this.interestOfferService = interestOfferService;
        this.interestAgentService = interestAgentService;
        this.interestUpdateService = interestUpdateService;
        this.resourceOwnershipService = resourceOwnershipService;
    }

    @GetMapping("/interest-offers")
    public List<InterestOfferSummary> offers(@PathVariable UUID userId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::offerNotFound);

        return interestOfferService.pending(userId);
    }

    @GetMapping("/interest-offers/{offerId}")
    public InterestOfferSummary offer(@PathVariable UUID userId, @PathVariable UUID offerId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::offerNotFound);

        return interestOfferService.get(userId, offerId);
    }

    @PostMapping("/interest-offers/{offerId}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public InterestAgentSummary accept(@PathVariable UUID userId,
                                       @PathVariable UUID offerId,
                                       @RequestBody(required = false) InterestAcceptRequest interestAcceptRequest,
                                       HttpServletRequest request) {
        requireCaller(userId, request, InterestException::offerNotFound);

        return interestOfferService.accept(userId, offerId, interestAcceptRequest);
    }

    @PostMapping("/interest-offers/{offerId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable UUID userId, @PathVariable UUID offerId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::offerNotFound);

        interestOfferService.decline(userId, offerId);
    }

    @GetMapping("/interest-agents")
    public List<InterestAgentSummary> agents(@PathVariable UUID userId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::agentNotFound);

        return interestAgentService.list(userId);
    }

    @PatchMapping("/interest-agents/{agentId}")
    public InterestAgentSummary update(@PathVariable UUID userId,
                                       @PathVariable UUID agentId,
                                       @RequestBody InterestAgentUpdateRequest interestAgentUpdateRequest,
                                       HttpServletRequest request) {
        requireCaller(userId, request, InterestException::agentNotFound);

        return interestAgentService.update(userId, agentId, interestAgentUpdateRequest);
    }

    @DeleteMapping("/interest-agents/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID userId, @PathVariable UUID agentId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::agentNotFound);

        interestAgentService.delete(userId, agentId);
    }

    @PostMapping("/interest-agents/{agentId}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InterestAgentSummary run(@PathVariable UUID userId, @PathVariable UUID agentId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::agentNotFound);

        return interestAgentService.runNow(userId, agentId);
    }

    @GetMapping("/interest-agents/{agentId}/updates")
    public Page<InterestUpdateSummary> updates(@PathVariable UUID userId,
                                               @PathVariable UUID agentId,
                                               Pageable pageable,
                                               HttpServletRequest request) {
        requireCaller(userId, request, InterestException::agentNotFound);

        return interestUpdateService.updates(userId, agentId, pageable);
    }

    @GetMapping("/interest-updates/by-chat/{chatId}")
    public InterestUpdateSummary updateByChat(@PathVariable UUID userId, @PathVariable UUID chatId, HttpServletRequest request) {
        requireCaller(userId, request, InterestException::updateNotFound);

        return interestUpdateService.byChat(userId, chatId);
    }

    private void requireCaller(UUID userId, HttpServletRequest request, Supplier<InterestException> notFound) {
        if (!resourceOwnershipService.isOwner(userId, request)) {
            throw notFound.get();
        }
    }
}
