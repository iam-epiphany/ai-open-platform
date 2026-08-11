package com.aiopenplatform.cache;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.aiopenplatform.entity.TokenActivity;
import com.aiopenplatform.entity.TokenSku;
import com.aiopenplatform.service.ITokenActivityService;
import com.aiopenplatform.service.ITokenSkuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.aiopenplatform.utils.RedisConstants.TOKEN_ACTIVITY_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_QUOTA_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_KEY;
import static com.aiopenplatform.utils.RedisConstants.TOKEN_SKU_TTL;

/**
 * binlog 驱动缓存同步（Canal 客户端）
 * <p>
 * MySQL 为事实源：业务代码只写 DB，本监听器订阅 canal-server 推送的 ROW binlog：
 * <ul>
 *     <li>tb_token_sku 详情变更 → 写透传 Memcache（L2）/Redis（L3）+ 广播 JVM 缓存失效（L1）；</li>
 *     <li>活动页聚合数据变更 / SKU 变更影响活动聚合 → 删除聚合 key，下一次读请求重建；</li>
 *     <li>tb_user_quota 变更 → 删除权益缓存 key；</li>
 * </ul>
 * 跨节点 JVM 缓存失效通过 Redis Pub/Sub（cache:invalidate 频道）广播，由 {@link CacheInvalidationListener} 接收。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "canal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BinlogCacheSyncListener implements InitializingBean, DisposableBean {

    @Value("${canal.server.host:127.0.0.1}")
    private String host;
    @Value("${canal.server.port:11111}")
    private int port;
    @Value("${canal.destination:example}")
    private String destination;
    @Value("${canal.batch-size:100}")
    private int batchSize;
    @Value("${canal.filter:token_platform\\.(tb_token_.*|tb_user_quota)}")
    private String filter;

    @Resource
    private ITokenSkuService tokenSkuService;
    @Resource
    private ITokenActivityService tokenActivityService;
    @Resource
    private MultiLevelCacheService multiLevelCacheService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "canal-binlog-listener"));
    private volatile boolean running = true;

    @Override
    public void afterPropertiesSet() {
        executor.submit(this::listen);
        log.info("Canal binlog 监听启动: {}:{}, destination={}, filter={}", host, port, destination, filter);
    }

    /**
     * 连接 canal-server，循环拉取 binlog 批次（官方手动客户端模式）
     * 断线自动重连；重连后 rollback 回滚到上次未 ACK 的位置，防止丢消息（重复由业务幂等兜底）
     */
    private void listen() {
        CanalConnector connector = null;
        while (running) {
            try {
                connector = CanalConnectors.newSingleConnector(
                        new InetSocketAddress(host, port), destination, "", "");
                connector.connect();
                connector.subscribe(filter);
                connector.rollback();
                log.info("Canal binlog 监听已连接: {}:{}", host, port);
                while (running) {
                    Message message = connector.getWithoutAck(batchSize);
                    long batchId = message.getId();
                    if (batchId == -1 || message.getEntries().isEmpty()) {
                        Thread.sleep(200);
                        continue;
                    }
                    processEntries(message.getEntries());
                    connector.ack(batchId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Canal 连接异常，5 秒后重连: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (connector != null) {
                    try {
                        connector.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     * 解析 binlog 批次，逐行分发到缓存同步逻辑
     */
    private void processEntries(List<CanalEntry.Entry> entries) {
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange;
            try {
                rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                log.error("解析 binlog 事件失败", e);
                continue;
            }
            String tableName = entry.getHeader().getTableName();
            CanalEntry.EventType eventType = rowChange.getEventType();
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                handleRow(tableName, eventType, rowData);
            }
        }
    }

    private void handleRow(String tableName, CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        switch (tableName) {
            case "tb_token_sku":
                handleSkuChange(eventType, rowData);
                break;
            case "tb_token_activity":
                handleActivityChange(eventType, rowData);
                break;
            case "tb_user_quota":
                handleQuotaChange(eventType, rowData);
                break;
            default:
                break;
        }
    }

    /**
     * SKU 详情变更：写透传 L2/L3 + 广播 L1 失效；同时删除包含该 SKU 的活动聚合 key
     */
    private void handleSkuChange(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        Long skuId = getColumnLong(rowData, "id");
        if (skuId == null) {
            return;
        }
        if (eventType == CanalEntry.EventType.DELETE) {
            // 删除：直接删缓存，避免残留旧数据
            multiLevelCacheService.delete(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + skuId);
        } else {
            // DB 为事实源，binlog 事件到达时数据已提交：重建详情并写透传
            TokenSku sku = tokenSkuService.getById(skuId);
            if (sku != null) {
                multiLevelCacheService.writeThrough(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + skuId, sku, TOKEN_SKU_TTL);
            } else {
                multiLevelCacheService.delete(JvmCaches.CACHE_SKU, TOKEN_SKU_KEY + skuId);
            }
        }
        // 活动聚合内嵌 SKU 详情：删除包含该 SKU 的所有活动聚合 key，下一次读取重建
        invalidateActivitiesContaining(skuId);
    }

    /**
     * 活动页变更：删除聚合 key（活动页聚合数据由读请求重建）
     */
    private void handleActivityChange(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        Long activityId = getColumnLong(rowData, "id");
        if (activityId == null) {
            return;
        }
        multiLevelCacheService.delete(JvmCaches.CACHE_ACTIVITY, TOKEN_ACTIVITY_KEY + activityId);
    }

    /**
     * 用户权益变更：删除权益缓存 key，下一次读取重建
     */
    private void handleQuotaChange(CanalEntry.EventType eventType, CanalEntry.RowData rowData) {
        Long userId = getColumnLong(rowData, "user_id");
        Long modelId = getColumnLong(rowData, "model_id");
        if (userId == null) {
            return;
        }
        multiLevelCacheService.delete(JvmCaches.CACHE_QUOTA, TOKEN_QUOTA_KEY + userId + ":" + (modelId == null ? 0 : modelId));
    }

    /**
     * 删除包含指定 SKU 的活动聚合缓存
     */
    private void invalidateActivitiesContaining(Long skuId) {
        List<TokenActivity> activities = tokenActivityService.lambdaQuery()
                .like(TokenActivity::getSkuIds, skuId)
                .list();
        for (TokenActivity activity : activities) {
            multiLevelCacheService.delete(JvmCaches.CACHE_ACTIVITY, TOKEN_ACTIVITY_KEY + activity.getId());
        }
    }

    /**
     * 从行数据中取指定列值（DELETE 事件取 before，其余取 after）
     */
    private Long getColumnLong(CanalEntry.RowData rowData, String columnName) {
        List<CanalEntry.Column> columns = rowData.getAfterColumnsList();
        if (columns == null || columns.isEmpty()) {
            columns = rowData.getBeforeColumnsList();
        }
        for (CanalEntry.Column column : columns) {
            if (columnName.equals(column.getName()) && column.getValue() != null) {
                try {
                    return Long.valueOf(column.getValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public void destroy() {
        running = false;
        executor.shutdownNow();
    }
}
