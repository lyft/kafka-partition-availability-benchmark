/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class TopicVerifier {
    private static final String METRIC_NAME = "topicsAwaitingCreation";

    private static final Logger log = LoggerFactory.getLogger(TopicVerifier.class);

    public static void checkTopic(AdminClient kafkaAdminClient,
                                  String topicName,
                                  short replicationFactor,
                                  final String clusterName,
                                  final String metricsNamespace,
                                  boolean isRetry)
            throws InterruptedException {

        if (!isRetry) {
            Metrics.gauge(metricsNamespace,
                    Tags.of(CustomOrderedTag.of("cluster", clusterName, 1),
                            CustomOrderedTag.of("metric", METRIC_NAME, 2)),
                    1);
        }
        while (true) {
            DescribeTopicsResult result = kafkaAdminClient.describeTopics(Collections.singleton(topicName));
            Map<String, TopicDescription> descriptionMap;
            try {
                descriptionMap = result.all().get();
            } catch (Exception unknownTopic) {
                if (!unknownTopic.getMessage().contains("UnknownTopicOrPartitionException")) {
                    log.error("UnexpectedException; trying again...", unknownTopic);
                    TimeUnit.SECONDS.sleep(1);
                    checkTopic(kafkaAdminClient, topicName, replicationFactor, clusterName, metricsNamespace, true);
                    break;
                }
                log.info("Topic {} not created yet, checking again in 1 second", topicName);
                TimeUnit.SECONDS.sleep(1);
                continue;
            }
            TopicPartitionInfo partition = descriptionMap.get(topicName).partitions().get(0);
            if (!partition.leader().isEmpty()
                    && partition.replicas().size() == replicationFactor
                    && partition.isr().size() >= 1) {
                Metrics.gauge(metricsNamespace,
                        Tags.of(CustomOrderedTag.of("cluster", clusterName, 1),
                                CustomOrderedTag.of("metric", METRIC_NAME, 2)),
                        -1);
                break;
            } else {
                log.info("Topic hasn't elected leader yet, checking again in 1 sec");
                log.info("Leader: {} Replicas: {} ISR: {}", partition.leader(), partition.replicas(), partition.isr());
                TimeUnit.SECONDS.sleep(1);
            }
        }
    }
}
