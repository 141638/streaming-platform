package com.streaming.stream.api.dto;

/**
 * A single social link on a channel profile.
 *
 * @param platform lowercase platform key (twitter, youtube, instagram, discord, tiktok, website)
 * @param url      the fully-qualified URL
 */
public record SocialLink(String platform, String url) {}
