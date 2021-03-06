package com.alibaba.otter.canal.client.adapter.es;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.otter.canal.client.adapter.OuterAdapter;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig.ESMapping;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfigLoader;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem;
import com.alibaba.otter.canal.client.adapter.es.config.SqlParser;
import com.alibaba.otter.canal.client.adapter.es.service.ESEtlService;
import com.alibaba.otter.canal.client.adapter.es.service.ESSyncService;
import com.alibaba.otter.canal.client.adapter.es.support.ESTemplate;
import com.alibaba.otter.canal.client.adapter.support.*;

/**
 * ES外部适配器
 *
 * @author rewerma 2018-10-20
 * @version 1.0.0
 */
@SPI("es")
public class ESAdapter implements OuterAdapter {

    private Map<String, ESSyncConfig>       esSyncConfig        = new LinkedHashMap<>(); // 文件名对应配置
    private Map<String, List<ESSyncConfig>> dbTableEsSyncConfig = new LinkedHashMap<>(); // schema-table对应配置

    private TransportClient                 transportClient;

    private ESSyncService                   esSyncService;

    public TransportClient getTransportClient() {
        return transportClient;
    }

    public ESSyncService getEsSyncService() {
        return esSyncService;
    }

    public Map<String, ESSyncConfig> getEsSyncConfig() {
        return esSyncConfig;
    }

    public Map<String, List<ESSyncConfig>> getDbTableEsSyncConfig() {
        return dbTableEsSyncConfig;
    }

    @Override
    public void init(OuterAdapterConfig configuration) {
        try {
            Map<String, ESSyncConfig> esSyncConfigTmp = ESSyncConfigLoader.load();
            // 过滤不匹配的key的配置
            esSyncConfigTmp.forEach((key, config) -> {
                if ((config.getOuterAdapterKey() == null && configuration.getKey() == null)
                    || (config.getOuterAdapterKey() != null
                        && config.getOuterAdapterKey().equalsIgnoreCase(configuration.getKey()))) {
                    esSyncConfig.put(key, config);
                }
            });

            for (ESSyncConfig config : esSyncConfig.values()) {
                SchemaItem schemaItem = SqlParser.parse(config.getEsMapping().getSql());
                config.getEsMapping().setSchemaItem(schemaItem);

                DruidDataSource dataSource = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
                if (dataSource == null || dataSource.getUrl() == null) {
                    throw new RuntimeException("No data source found: " + config.getDataSourceKey());
                }
                Pattern pattern = Pattern.compile(".*:(.*)://.*/(.*)\\?.*$");
                Matcher matcher = pattern.matcher(dataSource.getUrl());
                if (!matcher.find()) {
                    throw new RuntimeException("Not found the schema of jdbc-url: " + config.getDataSourceKey());
                }
                String schema = matcher.group(2);

                schemaItem.getAliasTableItems().values().forEach(tableItem -> {
                    List<ESSyncConfig> esSyncConfigs = dbTableEsSyncConfig
                        .computeIfAbsent(schema + "-" + tableItem.getTableName(), k -> new ArrayList<>());
                    esSyncConfigs.add(config);
                });
            }

            Map<String, String> properties = configuration.getProperties();
            Settings.Builder settingBuilder = Settings.builder();
            properties.forEach(settingBuilder::put);
            Settings settings = settingBuilder.build();
            transportClient = new PreBuiltTransportClient(settings);
            String[] hostArray = configuration.getHosts().split(",");
            for (String host : hostArray) {
                int i = host.indexOf(":");
                transportClient.addTransportAddress(new TransportAddress(InetAddress.getByName(host.substring(0, i)),
                    Integer.parseInt(host.substring(i + 1))));
            }
            ESTemplate esTemplate = new ESTemplate(transportClient);
            esSyncService = new ESSyncService(esTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void sync(Dml dml) {
        String database = dml.getDatabase();
        String table = dml.getTable();
        List<ESSyncConfig> esSyncConfigs = dbTableEsSyncConfig.get(database + "-" + table);
        esSyncService.sync(esSyncConfigs, dml);
    }

    @Override
    public EtlResult etl(String task, List<String> params) {
        EtlResult etlResult = new EtlResult();
        ESSyncConfig config = esSyncConfig.get(task);
        if (config != null) {
            DataSource dataSource = DatasourceConfig.DATA_SOURCES.get(config.getDataSourceKey());
            ESEtlService esEtlService = new ESEtlService(transportClient, config);
            if (dataSource != null) {
                return esEtlService.importData(params, false);
            } else {
                etlResult.setSucceeded(false);
                etlResult.setErrorMessage("DataSource not found");
                return etlResult;
            }
        } else {
            StringBuilder resultMsg = new StringBuilder();
            boolean resSuccess = true;
            // ds不为空说明传入的是datasourceKey
            for (ESSyncConfig configTmp : esSyncConfig.values()) {
                // 取所有的destination为task的配置
                if (configTmp.getDestination().equals(task)) {
                    ESEtlService esEtlService = new ESEtlService(transportClient, configTmp);
                    EtlResult etlRes = esEtlService.importData(params, false);
                    if (!etlRes.getSucceeded()) {
                        resSuccess = false;
                        resultMsg.append(etlRes.getErrorMessage()).append("\n");
                    } else {
                        resultMsg.append(etlRes.getResultMessage()).append("\n");
                    }
                }
            }
            if (resultMsg.length() > 0) {
                etlResult.setSucceeded(resSuccess);
                if (resSuccess) {
                    etlResult.setResultMessage(resultMsg.toString());
                } else {
                    etlResult.setErrorMessage(resultMsg.toString());
                }
                return etlResult;
            }
        }
        etlResult.setSucceeded(false);
        etlResult.setErrorMessage("Task not found");
        return etlResult;
    }

    @Override
    public Map<String, Object> count(String task) {
        ESSyncConfig config = esSyncConfig.get(task);
        ESMapping mapping = config.getEsMapping();
        SearchResponse response = transportClient.prepareSearch(mapping.get_index())
            .setTypes(mapping.get_type())
            .setSize(0)
            .get();

        long rowCount = response.getHits().getTotalHits();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("esIndex", mapping.get_index());
        res.put("count", rowCount);
        return res;
    }

    @Override
    public void destroy() {
        if (transportClient != null) {
            transportClient.close();
        }
    }

    @Override
    public String getDestination(String task) {
        ESSyncConfig config = esSyncConfig.get(task);
        if (config != null) {
            return config.getDestination();
        }
        return null;
    }
}
