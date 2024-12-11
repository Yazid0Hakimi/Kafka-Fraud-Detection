package ma.enset.kafkafrauddetection;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transactionsInputTopic() {
        return new NewTopic("transactions-input", 1, (short) 1);
    }

    @Bean
    public NewTopic fraudAlertsTopic() {
        return new NewTopic("fraud-alerts", 1, (short) 1);
    }
}
