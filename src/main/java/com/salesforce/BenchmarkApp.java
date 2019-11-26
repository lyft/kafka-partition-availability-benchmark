package com.salesforce;

import io.micrometer.core.instrument.*;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
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
            final Timer topicCreateTimeNanos = createTimer("topicCreateTimeNanos",
                    "Topic create time in nanos");
            // First message produce
            final Timer firstMessageProduceTimeNanos = createTimer("firstMessageProduceTimeNanos",
                    "First message produce latency time in nanos");
            // Message produce
            final Timer produceMessageTimeNanos = createTimer("produceMessageTimeNanos",
                    "Time it takes to produce messages in nanos");
            // Message consume
            final Timer consumerReceiveTimeNanos = createTimer("consumerReceiveTimeNanos",
                    "Time taken to do consumer.poll");
            // Offset commit
            final Timer consumerCommitTimeNanos = createTimer("consumerCommitTimeNanos",
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
            int readWriteIntervalMs = Integer.parseInt(settings.getProperty("read_write_interval_ms"));

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
            short replicationFactor = Short.parseShort(settings.getProperty("kafka.replication.factor"));
            String kafkaAcks = settings.getProperty("kafka.producer.acks");

            Map<String, Object> kafkaProducerConfig = new HashMap<>(kafkaAdminConfig);
            kafkaProducerConfig.put(ProducerConfig.ACKS_CONFIG, kafkaAcks);
            kafkaProducerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class.getName());
            kafkaProducerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

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
                    firstMessageProduceTimeNanos, produceMessageTimeNanos, consumerReceiveTimeNanos,
                    consumerCommitTimeNanos)));

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
                                firstMessageProduceTimeNanos, produceMessageTimeNanos,
                                consumerReceiveTimeNanos, consumerCommitTimeNanos);
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

                log.info("Starting benchmark...");
                for (int topic = 1; topic <= numTopics; topic++) {
                    createTopicFutures.put(createTopics.submit(new CreateTopic(topic, topicPrefix, kafkaAdminClient,
                            replicationFactor, clusterName, metricsNamespace, topicCreateTimeNanos)));
                    topicsCreated.increment();
                    if (createTopicFutures.size() >= numConcurrentTopicCreations) {
                        log.info("Created {} topics, ensuring success before producing more...", numConcurrentTopicCreations);
                        clearQueue(createTopicFutures, topicsCreateFailed);
                    }

                    if (writeTopics != null && writeFutures != null) {
                        writeFutures.put(writeTopics.submit(new WriteTopic(topic, topicPrefix, kafkaAdminClient,
                                replicationFactor, numMessagesToSendPerBatch,
                                keepProducing, kafkaProducer, readWriteIntervalMs, firstMessageProduceTimeNanos,
                                produceMessageTimeNanos, metricsNamespace, clusterName)));
                        topicsProduced.increment();
                    }


                    if (consumeTopics != null && consumerFutures != null) {
                        consumerFutures.put(consumeTopics.submit(new ConsumeTopic(topic, topicPrefix,
                                kafkaAdminClient, kafkaConsumerConfig, replicationFactor,
                                consumerReceiveTimeNanos, consumerCommitTimeNanos, metricsNamespace, clusterName,
                                readWriteIntervalMs)));
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
                                     Timer firstMessageProduceTimeNanos, Timer produceMessageTimeNanos,
                                     Timer consumerReceiveTimeNanos, Timer consumerCommitTimeNanos) {
        log.info("Stopping printing current accumulated metrics");
        log.info("Topics created: {}", topicsCreated.count());
        log.info("Topics create failed: {}", topicsCreateFailed.count());
        log.info("Topics produced to: {}", topicsProduced.count());
        log.info("Topics producing failed: {}", topicsProduceFailed.count());
        log.info("Topics consumed from: {}", topicsConsumed.count());
        log.info("Topics consuming failed: {}", topicConsumeFailed.count());

        log.info("Produced num: {}", produceMessageTimeNanos.count());
        log.info("First Message Produce percentiles: {}", Arrays.stream(firstMessageProduceTimeNanos.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                (double) TimeUnit.NANOSECONDS.toMicros(
                                        Double.valueOf(valueAtPercentile.value()).longValue()
                                ) / 1000,
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Produce percentiles: {}", Arrays.stream(produceMessageTimeNanos.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                (double) TimeUnit.NANOSECONDS.toMicros(
                                        Double.valueOf(valueAtPercentile.value()).longValue()
                                ) / 1000,
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Commit num: {}", consumerCommitTimeNanos.count());
        log.info("Commit percentiles: {}", Arrays.stream(consumerCommitTimeNanos.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                (double) TimeUnit.NANOSECONDS.toMicros(
                                        Double.valueOf(valueAtPercentile.value()).longValue()
                                ) / 1000,
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
        log.info("Consumed num: {}", consumerReceiveTimeNanos.count());
        log.info("E2E percentiles: {}", Arrays.stream(consumerReceiveTimeNanos.takeSnapshot().percentileValues())
                .map(valueAtPercentile ->
                        String.format("%sms at %s%%",
                                (double) TimeUnit.NANOSECONDS.toMicros(
                                        Double.valueOf(valueAtPercentile.value()).longValue()
                                ) / 1000,
                                valueAtPercentile.percentile() * 100))
                .collect(Collectors.joining(" ")));
    }

    private static int clearQueue(BlockingQueue<Future<Exception>> futures, Counter failedCounter)
            throws InterruptedException, ExecutionException {
        int runningTally = 0;
        while (!futures.isEmpty()) {
            if (futures.peek().isDone()) {
                Future<Exception> f = futures.take();
                log.debug("Waiting for {} to close", f.toString());
                Exception e = f.get();
                if (e != null) {
                    failedCounter.increment();
                    log.error("Fatal error:", e);
                    throw new ExecutionException(e);
                }

                runningTally++;
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
