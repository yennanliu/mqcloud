package com.sohu.tv.mq.rocketmq;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.PullMessageService;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.trace.AsyncTraceDispatcher;
import org.apache.rocketmq.client.trace.hook.ConsumeMessageTraceHookImpl;
import org.apache.rocketmq.common.ServiceState;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.utils.HttpTinyClient;
import org.apache.rocketmq.common.utils.HttpTinyClient.HttpResult;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.sohu.index.tv.mq.common.BatchConsumerCallback;
import com.sohu.index.tv.mq.common.ConsumerCallback;
import com.sohu.tv.mq.common.AbstractConfig;
import com.sohu.tv.mq.dto.ConsumerConfigDTO;
import com.sohu.tv.mq.dto.DTOResult;
import com.sohu.tv.mq.metric.ConsumeStatManager;
import com.sohu.tv.mq.netty.SohuClientRemotingProcessor;
import com.sohu.tv.mq.rocketmq.consumer.BatchMessageConsumer;
import com.sohu.tv.mq.rocketmq.consumer.IMessageConsumer;
import com.sohu.tv.mq.rocketmq.consumer.SingleMessageConsumer;
import com.sohu.tv.mq.rocketmq.consumer.deduplicate.DeduplicateSingleMessageConsumer;
import com.sohu.tv.mq.rocketmq.limiter.LeakyBucketRateLimiter;
import com.sohu.tv.mq.rocketmq.limiter.RateLimiter;
import com.sohu.tv.mq.rocketmq.limiter.SwitchableRateLimiter;
import com.sohu.tv.mq.rocketmq.limiter.TokenBucketRateLimiter;
import com.sohu.tv.mq.rocketmq.redis.IRedis;
import com.sohu.tv.mq.util.Constant;

/**
 * rocketmq 消费者
 * 
 * @Description: push封装
 * @author copy from indexmq
 * @date 2018年1月17日
 */
@SuppressWarnings("deprecation")
public class RocketMQConsumer extends AbstractConfig {

    // 支持一批消息消费
    private BatchConsumerCallback<?, ?> batchConsumerCallback;

    /**
     * 消费者
     */
    private DefaultMQPushConsumer consumer;

    @SuppressWarnings("rawtypes")
    private ConsumerCallback consumerCallback;

    /**
     * 是否重试
     */
    private boolean reconsume = true;

    /**
     * 是否debug
     */
    private boolean debug;

    // "tag1 || tag2 || tag3"
    private String subExpression = "*";

    // 是否顺序消费
    private boolean consumeOrderly = false;

    // 跳过重试消息时间，默认为-1，即不跳过
    private volatile long retryMessageResetTo = -1;
    
    // 消息限速器
    private RateLimiter rateLimiter;
    
    private ScheduledExecutorService clientConfigScheduledExecutorService;
    
    private Class<?> consumerParameterTypeClass;
    
    // 是否开启统计
    private boolean enableStats = true;
    
    // 重试消息跳过的key
    private String retryMessageSkipKey;
    
    // 消费去重
    private boolean deduplicate;
    
    // 消费去重窗口时间，默认3分钟
    private int deduplicateWindowSeconds = 3 * 60 + 10;
    
    // 幂等消费用的redis
    private IRedis redis;
    
    /**
     * 一个应用创建一个Consumer，由应用来维护此对象，可以设置为全局对象或者单例<br>
     * ConsumerGroupName需要由应用来保证唯一
     */
    public RocketMQConsumer(String consumerGroup, String topic) {
        this(consumerGroup, topic, false);
    }
    
    /**
     * 一个应用创建一个Consumer，由应用来维护此对象，可以设置为全局对象或者单例<br>
     * ConsumerGroupName需要由应用来保证唯一
     */
    public RocketMQConsumer(String consumerGroup, String topic, boolean useLeakyBucketRateLimiter) {
        super(consumerGroup, topic);
        consumer = new DefaultMQPushConsumer(consumerGroup);
        // 消费消息超时将会发回重试队列，超时时间由默认的15分钟修改为2小时
        consumer.setConsumeTimeout(2 * 60);
        // 初始化限速器
        if (useLeakyBucketRateLimiter) {
            initLeakyBucketRateLimiter();
        } else {
            initTokenBucketRateLimiter();
        }
        // 注册线程统计
        ConsumeStatManager.getInstance().register(getGroup());
        // 关闭最大等待时间
        getConsumer().setAwaitTerminationMillisWhenShutdown(10000);
    }

    public void start() {
        try {
            // 初始化配置
            initConfig(consumer);
            if (getClusterInfoDTO().isBroadcast()) {
                consumer.setMessageModel(MessageModel.BROADCASTING);
            }
            consumer.subscribe(topic, subExpression);

            // 构建消费者对象
            final IMessageConsumer<?> messageConsumer = getMessageConsumer();
            // 注册顺序或并发消费
            if (consumeOrderly) {
                consumer.registerMessageListener(new MessageListenerOrderly() {
                    public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs, ConsumeOrderlyContext context) {
                        return messageConsumer.consumeMessage(msgs, context);
                    }
                });
            } else {
                consumer.registerMessageListener(new MessageListenerConcurrently() {
                    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                            ConsumeConcurrentlyContext context) {
                        return messageConsumer.consumeMessage(msgs, context);
                    }
                });
            }
            // 初始化消费者参数类型
            initConsumerParameterTypeClass();
            // 初始化定时调度任务
            initScheduleTask();
            // 消费者启动
            consumer.start();
            // init after start
            initAfterStart();
            logger.info("topic:{} group:{} start", topic, group);
        } catch (MQClientException e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 从mqcloud更新动态配置
     */
    private void initScheduleTask() {
        // 数据采样线程
        clientConfigScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "updateConsumerConfigThread-" + getGroup());
            }
        });
        clientConfigScheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpResult result = HttpTinyClient.httpGet(
                            "http://" + getMqCloudDomain() + "/consumer/config/" + getGroup(), null, null, "UTF-8", 5000);
                    if (HttpURLConnection.HTTP_OK != result.code) {
                        logger.error("http response err: code:{},info:{}", result.code, result.content);
                        return;
                    }
                    DTOResult<ConsumerConfigDTO> dtoResult = JSON.parseObject(result.content, new TypeReference<DTOResult<ConsumerConfigDTO>>(){});
                    ConsumerConfigDTO consumerConfigDTO = dtoResult.getResult();
                    if(consumerConfigDTO == null) {
                        return;
                    }
                    // 1.更新重试跳过时间戳
                    if (consumerConfigDTO.getRetryMessageResetTo() != null && 
                            retryMessageResetTo != consumerConfigDTO.getRetryMessageResetTo()) {
                        setRetryMessageResetTo(consumerConfigDTO.getRetryMessageResetTo());
                    }
                    // 2.更新消费是否暂停
                    boolean needCheckPause = false;
                    if (consumerConfigDTO.getPause() != null) {
                        String pauseClientId = consumerConfigDTO.getPauseClientId();
                        // 停止所有实例
                        if (pauseClientId == null || pauseClientId.length() == 0) {
                            needCheckPause = true;
                        } else if (consumerConfigDTO.getPauseClientId()
                                .equals(consumer.getDefaultMQPushConsumerImpl().getmQClientFactory().getClientId())) { // 只停止当前实例
                            needCheckPause = true;
                        }
                    }
                    if (needCheckPause && consumer.getDefaultMQPushConsumerImpl().isPause() != consumerConfigDTO.getPause()) {
                        setPause(consumerConfigDTO.getPause());
                    }
                    // 3.更新限速
                    if (consumerConfigDTO.getEnableRateLimit() != null && 
                            isEnableRateLimit() != consumerConfigDTO.getEnableRateLimit()) {
                        setEnableRateLimit(consumerConfigDTO.getEnableRateLimit());
                    }
                    if (consumerConfigDTO.getPermitsPerSecond() != null) {
                        int rate = consumerConfigDTO.getPermitsPerSecond().intValue();
                        if (getRate() != rate) {
                            setRate(rate);
                        }
                    }
                    // 更新重试消息跳过的key
                    setRetryMessageSkipKey(consumerConfigDTO.getRetryMessageSkipKey());
                } catch (Throwable ignored) {
                    logger.warn("skipRetryMessage err:{}", ignored);
                }
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {
        DefaultMQPushConsumerImpl innerConsumer = consumer.getDefaultMQPushConsumerImpl();
		if (ServiceState.RUNNING != innerConsumer.getServiceState()) {
			logger.info("conusmer:{} state is {}, no need shutdown", getGroup(), innerConsumer.getServiceState());
			return;
		}
        // 1.首先关闭rebalance线程，不再接受新的队列变更等。
        ServiceThread thread = getField(MQClientInstance.class, "rebalanceService", innerConsumer.getmQClientFactory());
        thread.shutdown();
        // 2.接着标记拉取线程停止，不直接关闭是为了把拉下来的消息消费完毕。
        PullMessageService pull = innerConsumer.getmQClientFactory().getPullMessageService();
        pull.makeStop();
        // 4.如下为正常关闭流程
        consumer.shutdown();
        rateLimiter.shutdown();
        clientConfigScheduledExecutorService.shutdown();
        super.shutdown();
    }
    
    /**
     * 启动后，初始化某些逻辑
     */
    public void initAfterStart() {
        // 注册私有处理器
        MQClientInstance mqClientInstance = consumer.getDefaultMQPushConsumerImpl().getmQClientFactory();
        mqClientInstance.getMQClientAPIImpl().getRemotingClient()
                .registerProcessor(RequestCode.GET_CONSUMER_RUNNING_INFO,
                        new SohuClientRemotingProcessor(mqClientInstance), null);
    }
    
    /**
     * 获取类的字段实例
     * @param clz
     * @param field
     * @param obj
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> T getField(Class<?> clz, String field, Object obj){
        try {
            Field f = clz.getDeclaredField(field);
            f.setAccessible(true);
            return(T) f.get(obj);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Batch consumption size
     * 不建议设置该值，采用默认即可
     * @param consumeMessageBatchMaxSize
     */
    @Deprecated
    public void setConsumeMessageBatchMaxSize(int consumeMessageBatchMaxSize) {
        if (consumeMessageBatchMaxSize <= 0) {
            return;
        }
        // 批量消息消费才允许设置
        if (consumerCallback == null && batchConsumerCallback != null) {
            consumer.setConsumeMessageBatchMaxSize(consumeMessageBatchMaxSize);
        }
    }

    public void setConsumeFromWhere(ConsumeFromWhere consumeFromWhere) {
        consumer.setConsumeFromWhere(consumeFromWhere);
    }

    public void setReconsume(boolean reconsume) {
        this.reconsume = reconsume;
    }

    @SuppressWarnings("rawtypes")
    public void setConsumerCallback(ConsumerCallback consumerCallback) {
        this.consumerCallback = consumerCallback;
    }

    public void setConsumeTimestamp(String consumeTimestamp) {
        consumer.setConsumeTimestamp(consumeTimestamp);
    }

    public DefaultMQPushConsumer getConsumer() {
        return consumer;
    }

    /**
     * 消费线程数，默认20
     * 
     * @param num
     */
    public void setConsumeThreadMin(int num) {
        if (num <= 0) {
            return;
        }
        consumer.setConsumeThreadMin(num);
    }

    /**
     * 消费线程数，默认20
     * 该参数无用
     * @param num
     */
    @Deprecated
    public void setConsumeThreadMax(int num) {
        if (num <= 0) {
            return;
        }
        consumer.setConsumeThreadMax(num);
    }

    /**
     * 一次拉取多少个消息 ，默认32
     * 
     * @param size
     */
    public void setPullBatchSize(int size) {
        if (size < 0) {
            return;
        }
        consumer.setPullBatchSize(size);
    }

    /**
     * queue中缓存多少个消息时进行流控 ，默认1000
     * 
     * @param size
     */
    public void setPullThresholdForQueue(int size) {
        if (size < 0) {
            return;
        }
        consumer.setPullThresholdForQueue(size);
    }

    /**
     * queue中缓存多少M消息时进行流控 ，默认100
     * 
     * @param size
     */
    public void setPullThresholdSizeForQueue(int size) {
        if (size < 0) {
            return;
        }
        consumer.setPullThresholdSizeForQueue(size);
    }

    /**
     * topic维度缓存多少个消息时进行流控 ，默认-1，不限制
     * 
     * @param size
     */
    public void setPullThresholdForTopic(int size) {
        if (size < 0) {
            return;
        }
        consumer.setPullThresholdForTopic(size);
    }

    /**
     * topic维度缓存多少M消息时进行流控 ，默认-1，不限制
     * 
     * @param size
     */
    public void setPullThresholdSizeForTopic(int size) {
        if (size < 0) {
            return;
        }
        consumer.setPullThresholdSizeForTopic(size);
    }

    /**
     * 拉取消息的时间间隔，毫秒，默认为0
     * 
     * @param pullInterval
     */
    public void setPullInterval(int pullInterval) {
        consumer.setPullInterval(pullInterval);
    }

    @SuppressWarnings("rawtypes")
    public ConsumerCallback getConsumerCallback() {
        return consumerCallback;
    }

    @SuppressWarnings("unchecked")
    public <T, C> BatchConsumerCallback<T, C> getBatchConsumerCallback() {
        return (BatchConsumerCallback<T, C>) batchConsumerCallback;
    }

    @SuppressWarnings({"rawtypes"})
    public void setBatchConsumerCallback(BatchConsumerCallback batchConsumerCallback) {
        this.batchConsumerCallback = batchConsumerCallback;
    }

    /**
     * 1.8.3之后不用设置broadcast了，可以自动区分
     * 
     * @param broadcast
     */
    @Deprecated
    public void setBroadcast(boolean broadcast) {
    }

    public String getSubExpression() {
        return subExpression;
    }

    public void setSubExpression(String subExpression) {
        this.subExpression = subExpression;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isReconsume() {
        return reconsume;
    }

    public void setConsumeOrderly(boolean consumeOrderly) {
        this.consumeOrderly = consumeOrderly;
    }

    @Override
    protected int role() {
        return CONSUMER;
    }

    @Override
    protected void registerTraceDispatcher(AsyncTraceDispatcher traceDispatcher) {
        consumer.getDefaultMQPushConsumerImpl().registerConsumeMessageHook(
                new ConsumeMessageTraceHookImpl(traceDispatcher));
    }

    /**
     * traceEnabled is controlled by MQCloud
     * 
     * @param traceEnabled
     */
    @Deprecated
    public void setTraceEnabled(boolean traceEnabled) {
    }

    /**
     * Maximum amount of time in minutes a message may block the consuming
     * thread.
     */
    public void setConsumeTimeout(long consumeTimeout) {
        if (consumeTimeout <= 0) {
            return;
        }
        consumer.setConsumeTimeout(consumeTimeout);
    }

    /**
     * 是否开启vip通道
     * 
     * @param vipChannelEnabled
     */
    public void setVipChannelEnabled(boolean vipChannelEnabled) {
        consumer.setVipChannelEnabled(vipChannelEnabled);
    }

    public long getRetryMessageResetTo() {
        return retryMessageResetTo;
    }

    public void setRetryMessageResetTo(long retryMessageResetTo) {
        logger.info("topic:{}'s consumer:{} retryMessageReset {}->{}", getTopic(), getGroup(), this.retryMessageResetTo,
                retryMessageResetTo);
        this.retryMessageResetTo = retryMessageResetTo;
    }

    public String getRetryMessageSkipKey() {
        return retryMessageSkipKey;
    }

    public void setRetryMessageSkipKey(String retryMessageSkipKey) {
        if (this.retryMessageSkipKey == retryMessageSkipKey) {
            return;
        }
        if (this.retryMessageSkipKey != null && this.retryMessageSkipKey.equals(retryMessageSkipKey)) {
            return;
        }
        if (retryMessageSkipKey != null && retryMessageSkipKey.equals(this.retryMessageSkipKey)) {
            return;
        }
        logger.info("topic:{}'s consumer:{} retryMessageSkipKey {}->{}", getTopic(), getGroup(),
                this.retryMessageSkipKey, retryMessageSkipKey);
        this.retryMessageSkipKey = retryMessageSkipKey;
    }
    
    /**
     * 最大重新消费次数
     * 默认为16次
     * @param maxReconsumeTimes
     */
    public void setMaxReconsumeTimes(int maxReconsumeTimes) {
        consumer.setMaxReconsumeTimes(maxReconsumeTimes);
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public void setRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * 设置速率
     * @param permitsPerSecond
     */
    public void setRate(int permitsPerSecond) {
        if (permitsPerSecond < 1) {
            logger.warn("topic:{}'s consumer:{} qps:{} must >= 1", getTopic(), getGroup(), permitsPerSecond);
            return;
        }
        rateLimiter.setRate(permitsPerSecond);
    }
    
    public int getRate() {
        return rateLimiter.getRate();
    }
    
    public void setPause(boolean pause) {
        logger.info("topic:{}'s consumer:{} pause changed: {}->{}", getTopic(), getGroup(), isPause(), pause);
        consumer.getDefaultMQPushConsumerImpl().setPause(pause);
    }

    public boolean isPause() {
        return consumer.getDefaultMQPushConsumerImpl().isPause();
    }

    public void setEnableRateLimit(boolean enableRateLimit) {
        if (rateLimiter instanceof SwitchableRateLimiter) {
            ((SwitchableRateLimiter) rateLimiter).setEnabled(enableRateLimit);
        }
    }
    
    public boolean isEnableRateLimit() {
        if (rateLimiter instanceof SwitchableRateLimiter) {
            return ((SwitchableRateLimiter) rateLimiter).isEnabled();
        }
        return false;
    }
    
    @Deprecated
    public void setShutdownWaitMaxMillis(long shutdownWaitMaxMillis) {
    }

    /**
     * 初始化漏桶限速器
     */
    public void initLeakyBucketRateLimiter() {
        initRateLimiter(new LeakyBucketRateLimiter(group, 2 * consumer.getConsumeThreadMin(),
                Constant.LIMIT_CONSUME_TPS, TimeUnit.SECONDS));
    }
    
    /**
     * 初始化令牌桶限速器
     */
    public void initTokenBucketRateLimiter() {
        initRateLimiter(new TokenBucketRateLimiter(Constant.LIMIT_CONSUME_TPS));
    }
    
    public boolean isEnableStats() {
        return enableStats;
    }

    public void setEnableStats(boolean enableStats) {
        this.enableStats = enableStats;
    }

    /**
     * 初始化限速器
     * @param rateLimiter
     */
    public void initRateLimiter(RateLimiter rateLimiter) {
        SwitchableRateLimiter switchableRateLimiter = new SwitchableRateLimiter();
        switchableRateLimiter.setName(group);
        switchableRateLimiter.setRateLimiter(rateLimiter);
        this.rateLimiter = switchableRateLimiter;
    }
    
    public Class<?> getConsumerParameterTypeClass() {
        return consumerParameterTypeClass;
    }

    public void initConsumerParameterTypeClass() {
        consumerParameterTypeClass = detectConsumerParameterTypeClass();
    }
    
    /**
     * 获取消费者参数类型
     * @return
     */
    private Class<?> detectConsumerParameterTypeClass() {
        try {
            if(getConsumerCallback() != null) {
                return _getConsumerParameterTypeClass();
            }
            return _getBatchConsumerParameterTypeClass();
        } catch (Throwable e) {
            logger.warn("ignore, detect consumer parameter type failed:{}", e.toString());
        }
        return null;
    }
    
    /**
     * 获取消费者参数类型
     * @return
     */
    private Class<?> _getConsumerParameterTypeClass() {
        Method[] methods = getConsumerCallback().getClass().getMethods();
        for (Method method : methods) {
            if (!"call".equals(method.getName())) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (!method.getReturnType().equals(Void.TYPE)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2) {
                continue;
            }
            if (MessageExt.class != parameterTypes[1]) {
                continue;
            }
            logger.info("consumer:{}'s parameterTypeClass:{}", getGroup(), parameterTypes[0].getName());
            return parameterTypes[0];
        }
        return null;
    }
    
    /**
     * 获取消费者参数类型
     * @return
     */
    private Class<?> _getBatchConsumerParameterTypeClass() {
        Method[] methods = getBatchConsumerCallback().getClass().getMethods();
        for (Method method : methods) {
            if (!"call".equals(method.getName())) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (!method.getReturnType().equals(Void.TYPE)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1) {
                continue;
            }
            if (List.class != parameterTypes[0]) {
                continue;
            }
            
            Type[] interfaceTypes = getBatchConsumerCallback().getClass().getGenericInterfaces();
            Type type;
            if (interfaceTypes.length == 0) {
                type = getBatchConsumerCallback().getClass().getGenericSuperclass();
            } else {
                type = interfaceTypes[0];
            }
            Class<?> clz = null;
            if (ParameterizedType.class.isAssignableFrom(type.getClass())) {
                clz = (Class<?>)(((ParameterizedType) type).getActualTypeArguments())[0];
            }
            logger.info("consumer:{}'s parameterTypeClass:{}", getGroup(), clz);
            return clz;
        }
        return null;
    }
    
    private IMessageConsumer<?> getMessageConsumer() {
        if (getConsumerCallback() != null) {
            if (getRedis() != null) {
                if (MessageModel.CLUSTERING.equals(consumer.getMessageModel())) {
                    return new DeduplicateSingleMessageConsumer<>(this);
                } else {
                    logger.warn("consume message model is broadcasting, cannot use deduplication!");
                }
            }
            return new SingleMessageConsumer<>(this);
        }
        return new BatchMessageConsumer<>(this);
    }

    public boolean isDeduplicate() {
        return deduplicate;
    }

    public void setDeduplicate(boolean deduplicate) {
        this.deduplicate = deduplicate;
    }

    public int getDeduplicateWindowSeconds() {
        return deduplicateWindowSeconds;
    }

    public void setDeduplicateWindowSeconds(int deduplicateWindowSeconds) {
        this.deduplicateWindowSeconds = deduplicateWindowSeconds;
    }

    public IRedis getRedis() {
        return redis;
    }

    /**
     * 设置redis实例，用于幂等消费，请用 @RedisBuilder 构建 @IRedis 实例
     * @param redis
     */
    public void setRedis(IRedis redis) {
        this.redis = redis;
    }
}
