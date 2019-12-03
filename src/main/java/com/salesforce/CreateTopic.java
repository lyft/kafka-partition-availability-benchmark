/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce;

import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;

public class CreateTopic implements Callable<Exception> {
    private static final Logger log = LoggerFactory.getLogger(CreateTopic.class);

    private final int topicId;
    private final String key;
    private final AdminClient kafkaAdminClient;
    private final short replicationFactor;
    private final Timer topicCreateTimeMillis;
    private final String clusterName;
    private final String metricNamespace;

    public CreateTopic(int topicId, String key, AdminClient kafkaAdminClient, short replicationFactor,
                       String clusterName, String metricNamespace, Timer topicCreateTimeMillis) {
        this.topicId = topicId;
        this.key = key;
        this.kafkaAdminClient = kafkaAdminClient;
        this.replicationFactor = replicationFactor;
        this.topicCreateTimeMillis = topicCreateTimeMillis;
        this.clusterName = clusterName;
        this.metricNamespace = metricNamespace;
    }

    @Override
    public Exception call() throws Exception {
        String topicName = TopicName.createTopicName(key, topicId);

        // TODO: Allow numPartitions to be changed
        Set<NewTopic> topic = Collections.singleton(new NewTopic(topicName, 1, replicationFactor));
        kafkaAdminClient.createTopics(topic);

        // Wait for topic to be created and for leader election to happen
        topicCreateTimeMillis.record(() -> {
            try {
                TopicVerifier.checkTopic(kafkaAdminClient, topicName, replicationFactor,
                        clusterName, metricNamespace,false);
            } catch (InterruptedException e) {
                log.error("Unable to record topic creation", e);
            }
        });


        log.debug("Created topic {}", topic);
        return null;
    }
}
