package com.streaming.stream.api.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /v1/channels/{username}/profile}.
 *
 * @param bio         the broadcaster's channel bio / description (max 2000 chars)
 * @param socialLinks social platform links for the profile
 */
public record UpdateProfileRequest(
        @Size(max = 2000, message = "Bio must be at most 2000 characters")
        String bio,
        List<SocialLink> socialLinks
) {}
