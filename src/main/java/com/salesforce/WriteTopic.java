/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

class WriteTopic implements Callable<Exception> {
    private static final String AWAITING_PRODUCE_METRIC_NAME = "threadsAwaitingMessageProduce";

    private static final Logger log = LoggerFactory.getLogger(WriteTopic.class);

    private static final SimpleDateFormat formatter = new SimpleDateFormat("yyyyy-mm-dd hh:mm:ss");

    private final int topicId;
    private final String key;
    private final AdminClient kafkaAdminClient;
    private final short replicationFactor;
    private final KafkaProducer<Integer, byte[]> kafkaProducer;
    private final int numMessagesToSendPerBatch;
    private final boolean keepProducing;
    private final int readWriteInterval;
    private final Timer firstMessageProduceTimeMillis;
    private final Timer produceMessageTimeMillis;
    private final String metricsNamespace;
    private final String clusterName;
    private final int messageSize;


    /**
     * Produce messages thread constructor
     *
     * @param topicId                       Unique identifier for topic
     * @param key                           Key for the environment
     * @param kafkaAdminClient              Kafka admin client instance we use
     * @param replicationFactor             Kafka's replication factor for messages
     * @param numMessagesToSendPerBatch     Number of messages to produce continuously
     * @param keepProducing                 Whether we should produce one message only or keep produce thread alive and
     *                                      produce each readWriteInterval
     * @param kafkaProducer                 Kafka Producer instance we use
     * @param readWriteInterval             How long to wait between message production
     * @param firstMessageProduceTimeMillis  Time it takes to produce the first message
     * @param produceMessageTimeMillis       Time it takes to produce the remaining messages
     * @param metricsNamespace              The namespace of the metrics we emit
     * @param clusterName                   Name of the cluster we are testing on
     * @param messageSize                   Size of the messages we send
     */
    public WriteTopic(int topicId, String key, AdminClient kafkaAdminClient, short replicationFactor,
                      int numMessagesToSendPerBatch, boolean keepProducing,
                      KafkaProducer<Integer, byte[]> kafkaProducer, int readWriteInterval,
                      Timer firstMessageProduceTimeMillis, Timer produceMessageTimeMillis,
                      String metricsNamespace, String clusterName, int messageSize) {
        this.topicId = topicId;
        this.key = key;
        this.kafkaAdminClient = kafkaAdminClient;
        this.replicationFactor = replicationFactor;
        this.numMessagesToSendPerBatch = numMessagesToSendPerBatch;
        this.keepProducing = keepProducing;
        this.kafkaProducer = kafkaProducer;
        this.readWriteInterval = readWriteInterval;
        this.firstMessageProduceTimeMillis = firstMessageProduceTimeMillis;
        this.produceMessageTimeMillis = produceMessageTimeMillis;
        this.metricsNamespace = metricsNamespace;
        this.clusterName = clusterName;
        this.messageSize = messageSize;
    }

    @Override
    public Exception call() {
        String topicName = TopicName.createTopicName(key, topicId);

        try {
            TopicVerifier.checkTopic(kafkaAdminClient, topicName, replicationFactor,
                    clusterName, metricsNamespace, false);

            Map<String, String> messageData = new HashMap<>();
            char[] randomChars = new char[messageSize];
            Arrays.fill(randomChars, '9');
            messageData.put("Junk", String.valueOf(randomChars));
            final byte[] byteDataInit = (System.currentTimeMillis() + "" + new String(randomChars)).getBytes();


            // Produce one message to "warm" kafka up
            gaugeMetric(AWAITING_PRODUCE_METRIC_NAME, 1);
            firstMessageProduceTimeMillis.record(() ->
                    kafkaProducer.send(new ProducerRecord<>(topicName, topicId, byteDataInit)));
            log.debug("Produced first message to topic {}", topicName);

            while (keepProducing) {
                // TODO: Get this from properties
                for (int i = 0; i < numMessagesToSendPerBatch; i++) {
                    int finalI = i;
                    final byte[] byteData = new String(System.currentTimeMillis() + "" + new String(randomChars)).getBytes();
                    produceMessageTimeMillis.record(() -> {
                        Future<RecordMetadata> produce =
                                kafkaProducer.send(new ProducerRecord<>(topicName, finalI, byteData));
                        kafkaProducer.flush();
                        try {
                            produce.get();
                        } catch (InterruptedException | ExecutionException e) {
                            log.error("Failed to get record metadata after produce");
                        }
                    });
                    log.debug("{}: Produced message {}", formatter.format(new Date()), topicId);
                }
                gaugeMetric(AWAITING_PRODUCE_METRIC_NAME, -1);
                Thread.sleep(readWriteInterval);
                gaugeMetric(AWAITING_PRODUCE_METRIC_NAME, 1);
            }
            log.debug("Produce {} messages to topic {}", numMessagesToSendPerBatch, topicName);

            // TODO: Also keep producers around and periodically publish new messages
            return null;
        } catch (Exception e) {
            log.error("Failed to produce for topic {}", topicName, e);
            return e;
        }
    }

    private void gaugeMetric(String metricName, final int value) {
        Metrics.gauge(metricsNamespace,
                Tags.of(CustomOrderedTag.of("cluster", clusterName, 1),
                        CustomOrderedTag.of("metric", metricName, 2)),
                value);
    }
}
