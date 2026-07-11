package com.streaming.chat;

import com.streaming.chat.config.ChatCacheProperties;
import com.streaming.chat.config.ChatPbacProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ChatCacheProperties.class, ChatPbacProperties.class})
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
