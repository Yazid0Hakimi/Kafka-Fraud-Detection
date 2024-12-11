package ma.enset.kafkafrauddetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.time.Duration;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class KafkaFraudDetectionApplication implements SmartLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(KafkaFraudDetectionApplication.class);

    private KafkaStreams streams;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean running = false;

    @Value("${management.influx.metrics.export.token}")
    private String influxToken;

    @Value("${management.influx.metrics.export.org}")
    private String influxOrg;

    @Value("${management.influx.metrics.export.bucket}")
    private String influxBucket;

    public static void main(String[] args) {
        SpringApplication.run(KafkaFraudDetectionApplication.class, args);

        // Run the Transaction Producer
        runTransactionProducer();
    }

    @Bean
    public KafkaStreams kafkaStreams() {
        InfluxDBClient influxDBClient = InfluxDBClientFactory.create(
                "http://localhost:8086",
                influxToken.toCharArray(),
                influxOrg,
                influxBucket
        );

        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> transactions = builder.stream("transactions-input");

        KStream<String, String> suspiciousTransactions = transactions.filter((key, value) -> {
            try {
                logger.info("Processing transaction: {}", value);
                Transaction transaction = parseTransaction(value);

                if (transaction.getAmount() > 10000) {
                    writeSuspiciousTransactionToInfluxDB(influxDBClient, transaction);
                    return true;
                }
                logger.info("Transaction not suspicious: {}", transaction);
                return false;
            } catch (Exception e) {
                logger.error("Error processing transaction: {}", value, e);
                return false;
            }
        });

        suspiciousTransactions.to("fraud-alerts", Produced.with(Serdes.String(), Serdes.String()));

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "fraud-detection-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        streams = new KafkaStreams(builder.build(), props);
        return streams;
    }

    private void writeSuspiciousTransactionToInfluxDB(InfluxDBClient influxDBClient, Transaction transaction) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("suspicious_transactions")
                    .addTag("userId", transaction.getUserId())
                    .addField("amount", transaction.getAmount())
                    .time(parseTimestamp(transaction.getTimestamp()), WritePrecision.MS);

            writeApi.writePoint(point);
            logger.info("Suspicious transaction successfully written to InfluxDB: {}", transaction);
        } catch (Exception e) {
            logger.error("Error writing suspicious transaction to InfluxDB", e);
        }
    }

    private long parseTimestamp(String timestampStr) {
        return Instant.parse(timestampStr).toEpochMilli();
    }

    private Transaction parseTransaction(String value) throws Exception {
        return mapper.readValue(value, Transaction.class);
    }

    private static void runTransactionProducer() {
        Random random = new Random();

        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        Producer<String, String> producer = new KafkaProducer<>(props);

        try {
            for (int i = 0; i < 100; i++) {
                // Generate a random transaction amount between 1000 and 20000
                int amount = 1000 + random.nextInt(19000); // range: 1000 to 20000

                // Randomize the timestamp to get different minutes
                String transaction = String.format(
                        "{\"userId\": \"user%d\", \"amount\": %d, \"timestamp\": \"2024-12-10T20:%02d:00Z\"}",
                        i % 10,
                        amount,
                        i % 60 // Ensure minute is within 00-59
                );

                producer.send(new ProducerRecord<>("transactions-input", null, transaction));
                System.out.println("Sent: " + transaction);
            }
        } finally {
            producer.close();
        }

    }

    @Override
    public void start() {
        if (streams != null) {
            CountDownLatch latch = new CountDownLatch(1);
            streams.setStateListener((newState, oldState) -> {
                if (newState == KafkaStreams.State.RUNNING) {
                    latch.countDown();
                }
            });

            streams.start();

            try {
                if (latch.await(60, TimeUnit.SECONDS)) {
                    running = true;
                    logger.info("Kafka Streams started successfully");
                } else {
                    logger.error("Kafka Streams failed to start within 60 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted while waiting for Kafka Streams to start", e);
            }
        }
    }

    @Override
    public void stop() {
        if (streams != null) {
            streams.close(Duration.ofSeconds(10));
            running = false;
            logger.info("Kafka Streams stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
