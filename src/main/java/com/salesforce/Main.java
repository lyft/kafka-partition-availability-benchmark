/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.statsd.StatsdMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);


    public static void initGlobalMetricsRegistry() {
        MeterRegistry registry = new StatsdMeterRegistry(getStr -> null, Clock.SYSTEM);
        Metrics.globalRegistry.add(registry);
    }

    public static void main(String[] args) throws Exception {
        initGlobalMetricsRegistry();

        Properties settings = new Properties();
        try (InputStream defaults = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("kafka-partition-availability-benchmark.properties")) {
            settings.load(defaults);
        }

        // If you have the properties file in your home dir apply those as overrides
        Path userPropFile = Paths.get(System.getProperty("user.home"), ".kafka-partition-availability-benchmark.properties");
        if (Files.exists(userPropFile)) {
            log.info("Found {}", userPropFile);
            try (InputStream userProps = new FileInputStream(userPropFile.toFile())) {
                settings.load(userProps);
            }
        }

        final BenchmarkApp benchmarkApp = new BenchmarkApp(settings.getProperty("default_cluster_name"),
                settings.getProperty("default_metrics_namespace"), settings);
    }


}
