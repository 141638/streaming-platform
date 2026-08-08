package com.streaming.insight.api;

import com.streaming.insight.api.dto.SuggestionResponse;
import com.streaming.insight.application.SuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for cold-start suggestions.
 *
 * <p>Returns trending channels and categories computed from aggregated
 * engagement events. Phase A is cold-start only (same suggestions for
 * all users). Phase B will add personalized suggestions based on the
 * requesting user's affinity vector.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class SuggestionController {

    private final SuggestionService suggestionService;

    /**
     * Get trending channels and categories.
     *
     * <p>Response 200:
     * <pre>
     * {
     *   "channels": [
     *     { "channelUsername": "streamer1", "viewCount": 150, "score": 37.5 }
     *   ],
     *   "categories": [
     *     { "category": "gaming", "viewCount": 200, "score": 50.0 }
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/suggestions")
    public Mono<ResponseEntity<SuggestionResponse>> getSuggestions() {
        return Mono.zip(
                suggestionService.findTrendingChannels().collectList(),
                suggestionService.findTrendingCategories().collectList()
        ).map(tuple -> ResponseEntity.ok(
                SuggestionResponse.of(tuple.getT1(), tuple.getT2())));
    }
}
