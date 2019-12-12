package com.salesforce;

import io.micrometer.core.instrument.*;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.errors.UnsupportedSaslMechanismException;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.security.scram.internals.ScramMechanism;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BenchmarkApp implements Callable<Exception> {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkApp.class);
    private static final int PRINT_METRICS_PERIOD_SECS = 10;

    private final String clusterName;
    private final String metricsNamespace;
    private final Properties settings;

    public BenchmarkApp(final String clusterName, final String metricsNamespace,
                        final Properties settings) {
        this.clusterName = clusterName;
        this.metricsNamespace = metricsNamespace;
        this.settings = settings;
        org.apache.log4j.Logger.getLogger("kafka").setLevel(Level.WARN);
    }

    @Override
    public Exception call() {
        try {
            // Topic creates
            final Timer topicCreateTimeMillis = createTimer("topicCreateTimeMillis",
                    "Topic create time in nanos");
            // First message produce
            final Timer firstMessageProduceTimeMillis = createTimer("firstMessageProduceTimeMillis",
                    "First message produce latency time in nanos");
            // Message produce
            final Timer produceMessageTimeMillis = createTimer("produceMessageTimeMillis",
                    "Time it takes to produce messages in nanos");
            // Message consume
            final Timer consumerReceiveTimeMillis = createTimer("consumerReceiveTimeMillis",
                    "Time taken to do consumer.poll");
            // Offset commit
            final Timer consumerCommitTimeMillis = createTimer("consumerCommitTimeMillis",
                    "Time it takes to commit new offset");

            Integer numConcurrentTopicCreations = Integer.valueOf(settings.getProperty("num_concurrent_topic_creations"));
            Integer numConcurrentConsumers = Integer.valueOf(settings.getProperty("num_concurrent_consumers"));
            Integer numConcurrentProducers = Integer.valueOf(settings.getProperty("num_concurrent_producers"));
            Integer numTopics = Integer.valueOf(settings.getProperty("num_topics"));
            if (numConcurrentConsumers > numTopics) {
                log.error("You must set num_topics higher than or same as num_concurrent_consumers");
                System.exit(1);
            }
            if (numConcurrentProducers > numTopics) {
                log.error("You must set num_topics higher than or same as num_concurrent_producers");
                System.exit(2);
            }
            if (numConcurrentTopicCreations > numTopics) {
                log.error("You cannot concurrently create more topics than desired");
                System.exit(4);
            }

            String topicPrefix = settings.getProperty("default_topic_prefix");
            int writeIntervalMs = Integer.parseInt(settings.getProperty("write_interval_ms"));
            int readIntervalMs = Integer.parseInt(settings.getProperty("read_interval_ms"));
            int numMessagesToSendPerBatch = Integer.parseInt(settings.getProperty("messages_per_batch"));
            boolean keepProducing = Boolean.parseBoolean(settings.getProperty("keep_producing"));

            // Admin settings
            Map<String, Object> kafkaAdminConfig = new HashMap<>();
            kafkaAdminConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.getProperty("kafka.brokers"));

            // Consumer settings
            Map<String, Object> kafkaConsumerConfig = new HashMap<>(kafkaAdminConfig);
            kafkaConsumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class.getName());
            kafkaConsumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            kafkaConsumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, settings.getProperty("kafka.enable.auto.commit"));

            // Producer settings
            Map<String, Object> kafkaProducerConfig = new HashMap<>(kafkaAdminConfig);
            kafkaProducerConfig.put(ProducerConfig.ACKS_CONFIG, settings.getProperty("kafka.producer.acks"));
            kafkaProducerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class.getName());
            kafkaProducerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

            if (Boolean.valueOf(settings.getProperty("secure_clients_enabled"))) {
                kafkaConsumerConfig.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_SSL);
                kafkaConsumerConfig.put(SaslConfigs.SASL_MECHANISM, ScramMechanism.SCRAM_SHA_256);
                kafkaConsumerConfig.put(SaslConfigs.SASL_JAAS_CONFIG, settings.getProperty("sasl_jaas_config"));
                kafkaConsumerConfig.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, settings.getProperty("trust_store_location"));
                kafkaConsumerConfig.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, settings.getProperty("trust_store_pw"));
                kafkaConsumerConfig.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");

                kafkaProducerConfig.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_SSL);
                kafkaProducerConfig.put(SaslConfigs.SASL_MECHANISM, ScramMechanism.SCRAM_SHA_256);
                kafkaProducerConfig.put(SaslConfigs.SASL_JAAS_CONFIG, settings.getProperty("sasl_jaas_config"));
                kafkaProducerConfig.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, settings.getProperty("trust_store_location"));
                kafkaProducerConfig.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, settings.getProperty("trust_store_pw"));
                kafkaProducerConfig.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
            }

            // Global counters
            Counter topicsCreated = createCounter("numTopicsCreated",
                    "Number of topics we've attempted to create");
            Counter topicsCreateFailed = createCounter("numTopicsCreateFailed",
                    "Number of topics we've failed to create");
            Counter topicsProduced = createCounter("numTopicsProduced",
                    "Number of topics we've attempted to produce to");
            Counter topicsProduceFailed = createCounter("numTopicsProduceFailed",
                    "Number of topics we've failed to produce to");
            Counter topicsConsumed = createCounter("numTopicsConsumed",
                    "Number of topics we've attempted to consume from");
            Counter topicsConsumeFailed = createCounter("numTopicsConsumeFailed",
                    "Number of topics we've failed to consume from");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> printMetrics(topicsCreated, topicsCreateFailed,
                    topicsProduced, topicsProduceFailed, topicsConsumed, topicsConsumeFailed,
                    firstMessageProduceTimeMillis, produceMessageTimeMillis, consumerReceiveTimeMillis,
                    consumerCommitTimeMillis)));

            try (AdminClient kafkaAdminClient = KafkaAdminClient.create(kafkaAdminConfig);
                 KafkaProducer<Integer, byte[]> kafkaProducer = new KafkaProducer<>(kafkaProducerConfig)) {
                ExecutorService createTopics = Executors.newFixedThreadPool(numConcurrentTopicCreations);
                ExecutorService writeTopics = null;
                if (numConcurrentProducers > 0) {
                    writeTopics = Executors.newFixedThreadPool(numConcurrentProducers);
                }
                ExecutorService consumeTopics = null;
                if (numConcurrentConsumers > 0) {
                    consumeTopics = Executors.newFixedThreadPool(numConcurrentConsumers);
                }
                ExecutorService printMetrics = Executors.newSingleThreadExecutor();
                printMetrics.submit((Runnable) () -> {
                    while (true) {
                        try {
                            TimeUnit.SECONDS.sleep(PRINT_METRICS_PERIOD_SECS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException("Interrupted print metrics thread");
                        }
                        printMetrics(topicsCreated, topicsCreateFailed, topicsProduced,
                                topicsProduceFailed, topicsConsumed, topicsConsumeFailed,
                                firstMessageProduceTimeMillis, produceMessageTimeMillis,
                                consumerReceiveTimeMillis, consumerCommitTimeMillis);
                    }
                });

                BlockingQueue<Future<Exception>> createTopicFutures = new ArrayBlockingQueue<>(numConcurrentTopicCreations);
                BlockingQueue<Future<Exception>> writeFutures = null;
                if (writeTopics != null) {
                    writeFutures = new ArrayBlockingQueue<>(numConcurrentProducers);

                }
                BlockingQueue<Future<Exception>> consumerFutures = null;
                if (consumeTopics != null) {
                    consumerFutures = new ArrayBlockingQueue<>(numConcurrentConsumers);
                }

                short replicationFactor = Short.parseShort(settings.getProperty("kafka.replication.factor"));
                log.info("Starting benchmark...");
                for (int topic = 1; topic <= numTopics; topic++) {
                    createTopicFutures.put(createTopics.submit(new CreateTopic(topic, topicPrefix, kafkaAdminClient,
                            replicationFactor, clusterName, metricsNamespace, topicCreateTimeMillis)));
                    topicsCreated.increment();
                    if (createTopicFutures.size() >= numConcurrentTopicCreations) {
                        log.info("Created {} topics, ensuring success before producing more...", numConcurrentTopicCreations);
                        clearQueue(createTopicFutures, topicsCreateFailed);
                    }

                    if (writeTopics != null && writeFutures != null) {
                        writeFutures.put(writeTopics.submit(new WriteTopic(topic, topicPrefix, kafkaAdminClient,
                                replicationFactor, numMessagesToSendPerBatch,
                                keepProducing, kafkaProducer, writeIntervalMs, firstMessageProduceTimeMillis,
                                produceMessageTimeMillis, metricsNamespace, clusterName)));
                        topicsProduced.increment();
                    }


                    if (consumeTopics != null && consumerFutures != null) {
                        consumerFutures.put(consumeTopics.submit(new ConsumeTopic(topic, topicPrefix,
                                kafkaAdminClient, kafkaConsumerConfig, replicationFactor,
                                consumerReceiveTimeMillis, consumerCommitTimeMillis, metricsNamespace, clusterName,
                                readIntervalMs, keepProducing)));
                        topicsConsumed.increment();
                        if (consumerFutures.size() >= numConcurrentConsumers) {
                            log.debug("Consumed {} topics, clearing queue before consuming more...", numConcurrentConsumers);
                            clearQueue(consumerFutures, topicsConsumeFailed);
                        }
                    }

                    if (writeFutures != null && writeFutures.size() >= numConcurrentProducers) {
                        log.info("Produced {} topics, ensuring success before producing more...", numConcurrentProducers);
                        clearQueue(writeFutures, topicsProduceFailed);
                    }
                }

                createTopics.shutdown();
                try {
                    clearQueue(writeFutures, topicsProduceFailed);
                    clearQueue(consumerFutures, topicsConsumeFailed);
                } finally {
                    try {
                        writeTopics.shutdownNow();
                    } finally {
                        consumeTopics.shutdownNow();
                    }

                }
            }
        } catch (Exception e) {
            log.error("Failed to run benchmark", e);
            return new Exception("Failed to run benchmark", e);
        }
        return null;
    }

    private static void printMetrics(Counter topicsCreated, Counter topicsCreateFailed, Counter topicsProduced,
                                     Counter topicsProduceFailed, Counter topicsConsumed, Counter topicConsumeFailed,
                                     Timer firstMessageProduceTimeMillis, Timer produceMessageTimeMillis,
                                     Timer consumerReceiveTimeMillis, Timer consumerCommitTimeMillis) {
        log.info("Stopping printing current accumulated metrics");
        log.info("Topics created: {}", topicsCreated.count());
        log.info("Topics create failed: {}", topicsCreateFailed.count());
        log.info("Topics produced to: {}", topicsProduced.count());
        log.info("Topics producing failed: {}", topicsProduceFailed.count());
        log.info("Topics consumed from: {}", topicsConsumed.count());
        log.info("Topics consuming failed: {}", topicConsumeFailed.count());

        log.info("Produced num: {}", produceMessageTimeMillis.count());
        log.info("First Message Produce percentiles: {}", Arrays.stream(firstMessageProduceTimeMillis.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                Double.valueOf(valueAtPercentile.value()).toString(),
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Produce percentiles: {}", Arrays.stream(produceMessageTimeMillis.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                Double.valueOf(valueAtPercentile.value()).toString(),
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Commit num: {}", consumerCommitTimeMillis.count());
        log.info("Commit percentiles: {}", Arrays.stream(consumerCommitTimeMillis.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                Double.valueOf(valueAtPercentile.value()).toString(),
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Consumed num: {}", consumerReceiveTimeMillis.count());
        log.info("E2E percentiles: {}", Arrays.stream(consumerReceiveTimeMillis.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                Double.valueOf(valueAtPercentile.value()).toString(),
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
    }

    private static int clearQueue(BlockingQueue<Future<Exception>> futures, Counter failedCounter)
            throws InterruptedException, ExecutionException {
        int runningTally = 0;
        while (!futures.isEmpty()) {
            if (futures.peek().isDone()) {
                Future<Exception> f = futures.take();
                log.info("Waiting for {} to close", f.toString());
                Exception e = f.get();
                if (e != null) {
                    failedCounter.increment();
                    log.error("Fatal error:", e);
                    throw new ExecutionException(e);
                }

                runningTally++;
            } else {
                Thread.sleep(10000); // wait for 10 secs if not done.
            }
        }
        return runningTally;
    }

    private Counter createCounter(String name, String description) {
        return Counter
                .builder(metricsNamespace)
                .tags(getTagsForMetric(name))
                .description(description)
                .register(Metrics.globalRegistry);
    }

    private Timer createTimer(String name, String description) {
        return Timer
                .builder(metricsNamespace)
                .tags(getTagsForMetric(name))
                .description(description)
                .publishPercentiles(0.5, 0.95, 0.99, 0.999, 0.9999)
                .minimumExpectedValue(Duration.ofNanos(10))
                .maximumExpectedValue(Duration.ofMinutes(1))
                .register(Metrics.globalRegistry);
    }

    private Tags getTagsForMetric(String metricName) {
        return Tags.of(CustomOrderedTag.of("cluster", clusterName, 1),
                CustomOrderedTag.of("metric", metricName, 2));
    }
}
