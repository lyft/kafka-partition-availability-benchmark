/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce;

import io.micrometer.core.instrument.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

class ConsumeTopic implements Callable<Exception> {
    private static final String AWAITING_CONSUME_METRIC_NAME = "threadsAwaitingConsume";
    private static final String AWAITING_COMMIT_METRIC_NAME = "threadsAwaitingCommit";

    private static final Logger log = LoggerFactory.getLogger(ConsumeTopic.class);

    private final int topicId;
    private final String key;
    private final AdminClient kafkaAdminClient;
    private final Map<String, Object> kafkaConsumerConfig;
    private final short replicationFactor;
    private final Timer consumerReceiveTimeMillis;
    private final Timer consumerCommitTimeMillis;
    private final String metricsNamespace;
    private final String clusterName;
    private final int readWriteInterval;

    /**
     * @param topicId                  Each topic gets a numeric id.
     * @param key                      Prefix for topics created by this tool.
     * @param kafkaAdminClient         Kafka admin client we are using.
     * @param kafkaConsumerConfig      Map that contains consumer configuration.
     * @param replicationFactor        Replication factor of the topic to be created.
     * @param consumerCommitTimeMillis  Time it takes for the consumer to commit its offset.
     * @param consumerReceiveTimeMillis Time it takes for the consumer to receive the message.
     * @param metricsNamespace         The namespace to use when submitting metrics.
     * @param clusterName              Name of the cluster we are monitoring.
     * @param readWriteInterval   How long should we wait before polls for consuming new messages
     */
    public ConsumeTopic(int topicId, String key, AdminClient kafkaAdminClient,
                        Map<String, Object> kafkaConsumerConfig, short replicationFactor,
                        Timer consumerReceiveTimeMillis, Timer consumerCommitTimeMillis,
                        String metricsNamespace, String clusterName, int readWriteInterval) {
        this.topicId = topicId;
        this.key = key;
        this.kafkaAdminClient = kafkaAdminClient;
        this.kafkaConsumerConfig = Collections.unmodifiableMap(kafkaConsumerConfig);
        this.replicationFactor = replicationFactor;
        this.consumerReceiveTimeMillis = consumerReceiveTimeMillis;
        this.consumerCommitTimeMillis = consumerCommitTimeMillis;
        this.metricsNamespace = metricsNamespace;
        this.clusterName = clusterName;
        this.readWriteInterval = readWriteInterval;
    }

    @Override
    public Exception call() {
        String topicName = TopicName.createTopicName(key, topicId);
        try {
            TopicVerifier.checkTopic(kafkaAdminClient, topicName, replicationFactor,
                    clusterName, metricsNamespace, false);

            Map<String, Object> consumerConfigForTopic = new HashMap<>(kafkaConsumerConfig);
            consumerConfigForTopic.put(ConsumerConfig.GROUP_ID_CONFIG, topicName);
            KafkaConsumer<Integer, byte[]> consumer = new KafkaConsumer<>(consumerConfigForTopic);
            TopicPartition topicPartition = new TopicPartition(topicName, 0);
            consumer.assign(Collections.singleton(topicPartition));
            // Always start from end, since otherwise we'll play the catch up game.
            // Our goal here is not to monitor the lag.
            consumer.seekToEnd(Collections.singleton(topicPartition));

            gaugeMetric(AWAITING_CONSUME_METRIC_NAME, 1);
            while (true) {
                ConsumerRecords<Integer, byte[]> messages = consumer.poll(Duration.ofMillis(100));
                if (messages.count() == 0) {
                    log.debug("No messages detected on {}", topicName);
                    continue;
                }
                gaugeMetric(AWAITING_CONSUME_METRIC_NAME, -1);

                AtomicLong lastOffset = new AtomicLong();
                log.debug("Consuming {} records", messages.records(topicPartition).size());
                messages.records(topicPartition).forEach(consumerRecord -> {
                            consumerReceiveTimeMillis.record(Duration.ofMillis(System.currentTimeMillis() - consumerRecord.timestamp()));
                            lastOffset.set(consumerRecord.offset());
                        });

                gaugeMetric(AWAITING_COMMIT_METRIC_NAME, 1);
                consumerCommitTimeMillis.record(() ->
                        consumer.commitSync(Collections.singletonMap(topicPartition,
                                new OffsetAndMetadata(lastOffset.get() + 1))));
                gaugeMetric(AWAITING_COMMIT_METRIC_NAME, -1);

                consumer.seek(topicPartition, lastOffset.get() + 1);

                ConsumerRecord<Integer, byte[]> lastMessage =
                        messages.records(topicPartition).get(messages.count() - 1);
                String lastValue = new String(lastMessage.value());
                String truncatedValue = lastValue.length() <= 15 ? lastValue : lastValue.substring(0, 15);
                log.debug("Last consumed message {} -> {}..., consumed {} messages, topic: {}",
                        lastMessage.key(), truncatedValue, messages.count(), topicName);
                gaugeMetric(AWAITING_CONSUME_METRIC_NAME, 1);
            }
        } catch (Exception e) {
            log.error("Failed consume", e);
            return new Exception("Failed consume on topicName " + topicId, e);
        }
    }

    private void gaugeMetric(String metricName, final int value) {
        Metrics.gauge(metricsNamespace,
                Tags.of(CustomOrderedTag.of("cluster", clusterName, 1),
                        CustomOrderedTag.of("metric", metricName, 2)),
                value);
    }
}
