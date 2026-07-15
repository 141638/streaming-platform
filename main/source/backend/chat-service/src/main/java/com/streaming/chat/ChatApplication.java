package com.streaming.chat;

import com.streaming.chat.config.ChatCacheProperties;
import com.streaming.chat.config.ChatPbacProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableConfigurationProperties({ChatCacheProperties.class, ChatPbacProperties.class})
@EnableKafka
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
