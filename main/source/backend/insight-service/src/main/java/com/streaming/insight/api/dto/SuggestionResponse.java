package com.streaming.insight.api.dto;

import com.streaming.insight.domain.model.CategorySuggestion;
import com.streaming.insight.domain.model.ChannelSuggestion;
import java.util.List;

/**
 * REST response for {@code GET /v1/suggestions}.
 *
 * @param channels   trending channel suggestions, ordered by score descending
 * @param categories trending category suggestions, ordered by score descending
 */
public record SuggestionResponse(
        List<ChannelSuggestion> channels,
        List<CategorySuggestion> categories
) {
    public static SuggestionResponse of(List<ChannelSuggestion> channels,
                                        List<CategorySuggestion> categories) {
        return new SuggestionResponse(channels, categories);
    }
}
