package com.streaming.stream;

import com.streaming.stream.config.HeartbeatHarvestProperties;
import com.streaming.stream.config.ViewCountProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties({ViewCountProperties.class, HeartbeatHarvestProperties.class})
public class StreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamApplication.class, args);
    }
}
