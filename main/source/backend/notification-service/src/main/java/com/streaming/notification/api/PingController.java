package com.streaming.notification.api;

import com.streaming.common.api.ApiMessage;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class PingController {

    @GetMapping("/ping")
    public Mono<ApiMessage> ping() {
        return Mono.just(new ApiMessage("notification-service", "ok"));
    }

}
