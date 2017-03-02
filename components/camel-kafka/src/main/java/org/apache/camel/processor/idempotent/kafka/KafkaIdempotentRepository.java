package org.apache.camel.processor.idempotent.kafka;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.api.management.ManagedOperation;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.spi.ExecutorServiceManager;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.util.LRUCache;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StringHelper;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Kafka topic-based implementation of {@link org.apache.camel.spi.IdempotentRepository}.
 *
 * Uses a local cache of previously seen Message IDs. All mutations of the cache are via a Kafka topic, on which
 * additions and removals are broadcast. The topic used must be unique per repository instance. This class makes no
 * assumptions about number of partitions (it is designed to consume from all at the same time), or replication factor.
 * Each repository instance that uses the topic (e.g. on different machines running in parallel) controls its own
 * consumer group.
 *
 * On startup, the instance subscribes to the topic and rewinds the offset to the beginning, rebuilding the cache to the
 * latest state.
 *
 * To use, this repository must be placed in the Camel registry, either manually or by registration as a bean in
 * Spring/Blueprint, as it is CamelContext aware.
 *
 * @author jkorab
 */
@ManagedResource(description = "Kafka IdempotentRepository")
public class KafkaIdempotentRepository extends ServiceSupport implements IdempotentRepository<String>, CamelContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaIdempotentRepository.class);
    private final Map<String, Object> cache = Collections.synchronizedMap(new LRUCache<>(1000));

    private final AtomicLong duplicateCount = new AtomicLong(0);
    private final String topic;
    private final Properties producerConfig;
    private final Properties consumerConfig;

    private Consumer<String, String> consumer;
    private Producer<String, String> producer;
    private TopicPoller topicPoller;

    private CamelContext camelContext;
    private ExecutorService executorService;
    private CountDownLatch cacheReadyLatch;

    enum CacheAction {
        add,
        remove,
        clear
    }

    public KafkaIdempotentRepository(String topic, String bootstrapServers) {
        StringHelper.notEmpty(topic, "topic");
        StringHelper.notEmpty(bootstrapServers, "bootstrapServers");
        Properties consumerConfig = new Properties();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        Properties producerConfig = new Properties();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        this.topic = topic;
        this.consumerConfig = consumerConfig;
        this.producerConfig = producerConfig;
    }

    public KafkaIdempotentRepository(String topic, Properties consumerConfig, Properties producerConfig) {
        StringHelper.notEmpty(topic, "topic");
        this.topic = topic;
        ObjectHelper.notNull(consumerConfig, "consumerConfig");
        this.consumerConfig = consumerConfig;
        ObjectHelper.notNull(producerConfig, "producerConfig");
        this.producerConfig = producerConfig;
    }

    @Override
    protected void doStart() throws Exception {
        LOG.info("Context: {}", camelContext);

        // each consumer instance must have control over its own offset, so assign a groupID at random
        String groupId = UUID.randomUUID().toString();
        LOG.debug("Creating consumer with {}[{}]", ConsumerConfig.GROUP_ID_CONFIG, groupId);

        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.TRUE.toString());
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        consumer = new KafkaConsumer<>(consumerConfig);

        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // set up the producer to remove all batching on send
        producerConfig.putIfAbsent(ProducerConfig.ACKS_CONFIG, "1");
        producerConfig.putIfAbsent(ProducerConfig.BATCH_SIZE_CONFIG, "0");
        producer = new KafkaProducer<>(producerConfig);

        cacheReadyLatch = new CountDownLatch(1);
        topicPoller = new TopicPoller(consumer, cacheReadyLatch);
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        // doStart() has already been called at this point
        this.camelContext = camelContext;
        ExecutorServiceManager executorServiceManager = camelContext.getExecutorServiceManager();
        executorService = executorServiceManager.newFixedThreadPool(this, "KafkaIdempotentRepository", 1);
        executorService.submit(topicPoller);
        LOG.info("Warming up cache");
        try {
            if (cacheReadyLatch.await(30, TimeUnit.SECONDS)) {
                LOG.info("Cache OK");
            } else {
                LOG.warn("Timeout waiting for cache warm-up from topic {}. Proceeding anyway. " +
                        "Duplicate records may not be detected.", topic);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    @Override
    public CamelContext getCamelContext() {
        return this.camelContext;
    }

    @Override
    protected void doStop() {
        // stop the thread
        topicPoller.setRunning(false);
        try {
            if (topicPoller.getShutdownLatch().await(30, TimeUnit.SECONDS)) {
                LOG.info("Expired waiting on topicPoller to shut down");
            }
        } catch (InterruptedException e) {
            LOG.info("Interrupted waiting on latch: {}", e.getMessage());
        }
        executorService.shutdown();

        try {
            consumer.close();
        } finally {
            producer.close();
        }
    }

    @Override
    public boolean add(String key) {
        if (cache.containsKey(key)) {
            duplicateCount.incrementAndGet();
            return false;
        } else {
            broadcastAction(key, CacheAction.add);
            return true;
        }
    }

    private void broadcastAction(String key, CacheAction action) {
        try {
            LOG.debug("Broadcasting action:{} for key:{}", action, key);
            producer.send(new ProducerRecord<>(topic, key, action.toString())).get(); // sync send
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @ManagedOperation(description = "Does the store contain the given key")
    public boolean contains(String key) {
        LOG.debug("Checking cache for key:{}", key);
        boolean containsKey = cache.containsKey(key);
        if (containsKey) {
            duplicateCount.incrementAndGet();
        }
        return containsKey;
    }

    @Override
    @ManagedOperation(description = "Remove the key from the store")
    public boolean remove(String key) {
        broadcastAction(key, CacheAction.remove);
        return true;
    }

    @Override
    public boolean confirm(String key) {
        return true; // no-op
    }

    @Override
    public void clear() {
        broadcastAction(null, CacheAction.clear);
    }

    @ManagedOperation(description = "Number of times duplicate messages have been detected")
    public long getDuplicateCount() {
        return duplicateCount.get();
    }

    @ManagedOperation(description = "Number of times duplicate messages have been detected")
    public boolean isPollerRunning() {
        return topicPoller.getRunning();
    }

    private class TopicPoller implements Runnable {

        public static final int POLL_DURATION_MS = 10;

        private final Logger LOG = LoggerFactory.getLogger(this.getClass());
        private final Consumer<String, String> consumer;
        private final CountDownLatch cacheReadyLatch;

        private CountDownLatch shutdownLatch = new CountDownLatch(1);
        private AtomicBoolean running = new AtomicBoolean(true);

        TopicPoller(Consumer<String, String> consumer, CountDownLatch cacheReadyLatch) {
            this.consumer = consumer;
            this.cacheReadyLatch = cacheReadyLatch;
        }

        @Override
        public void run() {
            LOG.debug("Subscribing consumer to {}", topic);
            consumer.subscribe(Collections.singleton(topic));
            LOG.debug("Seeking to beginning");
            consumer.seekToBeginning(consumer.assignment());

            POLL_LOOP: while (running.get()) {
                LOG.trace("Polling");
                ConsumerRecords<String, String> consumerRecords = consumer.poll(POLL_DURATION_MS);
                if (consumerRecords.isEmpty()) {
                    // the first time this happens, we can assume that we have consumed all
                    // messages up to this point
                    LOG.trace("0 messages fetched on poll");
                    if (cacheReadyLatch.getCount() > 0) {
                        LOG.debug("Cache warmed up");
                        cacheReadyLatch.countDown();
                    }
                }
                for (ConsumerRecord<String, String> consumerRecord: consumerRecords) {
                    CacheAction action;
                    try {
                        action = CacheAction.valueOf(consumerRecord.value());
                    } catch (IllegalArgumentException iax) {
                        LOG.error("Unexpected action value:\"{}\" received on [topic:{}, partition:{}, offset:{}]. Shutting down.",
                                consumerRecord.key(), consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset());
                        setRunning(false);
                        continue POLL_LOOP;
                    }
                    String messageId = consumerRecord.key();
                    if (action == CacheAction.add) {
                        LOG.debug("Adding to cache messageId:{}", messageId);
                        cache.put(messageId, messageId);
                    } else if (action == CacheAction.remove) {
                        LOG.debug("Removing from cache messageId:{}", messageId);
                        cache.remove(messageId);
                    } else if (action == CacheAction.clear) {
                        cache.clear();
                    } else {
                        // this should never happen
                        LOG.error("No idea how to {} a record. Shutting down.", action);
                        setRunning(false);
                        continue POLL_LOOP;
                    }
                }

            }
            LOG.debug("TopicPoller finished - triggering shutdown latch");
            shutdownLatch.countDown();
        }

        public CountDownLatch getShutdownLatch() {
            return shutdownLatch;
        }

        public void setRunning(boolean running) {
            this.running.set(running);
        }

        public boolean getRunning() {
            return running.get();
        }
    }
}