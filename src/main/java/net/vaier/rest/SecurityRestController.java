package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * The security view's server side (#329 Slice 3): who CrowdSec is currently keeping out, and the two
 * things the operator can do about it — let one address back in now, or trust it for good.
 *
 * <p>Every path here is non-whitelisted, so Traefik's tier-3 catch-all puts it behind the admin auth chain
 * like every other fleet endpoint. There is no Java-side auth in this project and none is added here;
 * equally, nothing here is added to any anonymous allowlist. Who is blocked, and the power to unblock
 * them, is never anonymous.
 *
 * <p>Validation is the domain's: {@code SourceAddress.of} admits nothing but a dotted quad, so a
 * malformed or shell-flavoured address never reaches an exec argument and surfaces here as a uniform
 * {@code 400} through {@link GlobalExceptionHandler}. A failed unban surfaces as {@code 502} — the far
 * side refused — and never as a quiet success. A failed <em>read</em> surfaces as {@code 502} for the same
 * reason: "nobody is blocked" is what an empty list says on screen, and only CrowdSec gets to say it.
 */
@RestController
@RequestMapping("/security")
@RequiredArgsConstructor
@Slf4j
public class SecurityRestController {

    private final GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    private final LiftBlockUseCase liftBlockUseCase;
    private final TrustAddressUseCase trustAddressUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final ObjectMapper objectMapper;

    /**
     * Who is blocked right now, so the view can paint on load or reconnect. A read that fails is a
     * {@code 502}, not an empty list — the view renders empty as "nobody is blocked right now".
     */
    @GetMapping("/decisions")
    public List<BlockDecisionResponse> decisions() {
        return responses(getBlockDecisionsUseCase.getBlockDecisions());
    }

    /**
     * The security view's SSE stream. The browser never polls: it opens this and repaints on the
     * {@code block-decisions} events {@link BreachAttemptWatcher} pushes every sweep — and on the one a
     * mutation below pushes immediately, so an unblock is visible at once rather than up to five minutes
     * later.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return forSubscribingToEvents.subscribe(BreachAttemptWatcher.SECURITY_TOPIC);
    }

    /** Let one address back in now. One-off — the next matching scenario may block it again. */
    @DeleteMapping("/decisions/{sourceIp}")
    public ResponseEntity<Void> liftBlock(@PathVariable String sourceIp) {
        log.info("Lifting the block on {}", LogSafe.forLog(sourceIp));
        liftBlockUseCase.liftBlock(sourceIp);
        publishDecisions();
        return ResponseEntity.ok().build();
    }

    /**
     * Trust one address for good. It is unblocked now and joins the trusted networks; the whitelist itself
     * takes effect from CrowdSec's next restart, which Vaier deliberately does not trigger — see
     * {@link TrustAddressUseCase}.
     */
    @PostMapping("/trusted-addresses")
    public ResponseEntity<Void> trustAddress(@RequestBody TrustAddressRequest request) {
        log.info("Permanently trusting {}", LogSafe.forLog(request.sourceIp()));
        trustAddressUseCase.trustAddress(request.sourceIp());
        publishDecisions();
        return ResponseEntity.ok().build();
    }

    /**
     * Push the decisions as they stand now, so the view updates the moment something changed instead of
     * waiting for the next five-minute sweep — the {@code PublishedServiceRestController} pattern: act,
     * then publish. Called only after the action succeeded; a failed action publishes nothing, since a
     * refreshed list would read as though something had happened.
     *
     * <p>A push that fails must not turn a completed unban into an error response, so it is logged and
     * dropped — the next sweep repaints anyway.
     */
    private void publishDecisions() {
        try {
            forPublishingEvents.publish(BreachAttemptWatcher.SECURITY_TOPIC,
                BreachAttemptWatcher.DECISIONS_EVENT,
                objectMapper.writeValueAsString(responses(getBlockDecisionsUseCase.getBlockDecisions())));
        } catch (Exception e) {
            log.debug("Publishing the refreshed block decisions failed: {}", e.getMessage());
        }
    }

    static List<BlockDecisionResponse> responses(List<BlockDecision> decisions) {
        return decisions.stream().map(BlockDecisionResponse::from).toList();
    }

    /** Which address to trust for good. */
    record TrustAddressRequest(String sourceIp) {}

    /**
     * One active ban as the browser sees it — the same shape the REST read returns and the SSE topic
     * carries, built in one place so the two can never drift apart.
     *
     * <p>{@code locatable} and {@code enriched} are carried as the domain decided them, <b>not</b> left
     * for the browser to re-derive from {@code latitude}/{@code longitude}. That matters concretely:
     * CrowdSec writes {@code 0}/{@code 0} for a source it could not place, {@code 0} is falsy in
     * JavaScript, and a frontend truthiness check would quietly destroy the deliberate single-axis
     * carve-out in {@code BlockDecision.locatable()} — a genuine zero on one axis is a real place. The raw
     * coordinates ride along because the map needs numbers to draw with; the <em>decision</em> whether to
     * draw at all is already made.
     */
    record BlockDecisionResponse(Long id, String scenario, String sourceIp, String type, String duration,
                                 String country, String asnOrg, Double latitude, Double longitude,
                                 boolean enriched, boolean locatable, String origin, String label) {

        static BlockDecisionResponse from(BlockDecision decision) {
            return new BlockDecisionResponse(decision.id(), decision.scenario(), decision.sourceIp(),
                decision.type(), decision.duration(), decision.country(), decision.asnOrg(),
                decision.latitude(), decision.longitude(),
                decision.enriched(), decision.locatable(), decision.origin(), decision.label());
        }
    }
}
