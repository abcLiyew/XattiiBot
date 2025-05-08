package com.esdllm.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.esdllm.mapper.ConfigMapper;
import com.esdllm.model.Admin;
import com.esdllm.model.Config;
import com.esdllm.service.AdminService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加载数据库中的配置
 */
@Slf4j
@Component
public class LoadDSConfig {

    @Value(value = "${bot.qq}")
    Long botQQ;
    @Value(value = "${bot.admin}")
    Long admin;

    @Resource
    private ConfigMapper configMapper;

    @Resource
    private AdminService adminService;
    // 使用线程安全的ConcurrentHashMap存储配置
    @Getter
    private final Map<String, String> configMap = new ConcurrentHashMap<>();

    /**
     * 在Bean初始化完成后加载数据库中的配置
     */
    @PostConstruct
    public void load() {
        if (botQQ!=null&&botQQ>0) {
            updateConfig("botQQ",botQQ.toString());
        }
        if (admin!=null&&admin>0) {
            Admin admin1 = new Admin();
            admin1.setQqUid(admin);
            if (!adminService.save(admin1)) {
                log.error("添加管理员QQ失败");
            }
        }
        List<Config> configs = configMapper.selectList(null);
        for (Config config : configs) {
            configMap.put(config.getKey(), config.getValue());
        }
        log.info("加载数据库中的配置完成，配置数量: {}", configMap.size());
    }

    /**
     * 更新数据库中的配置
     * @param key 配置键
     * @param value 配置值
     */
    public void updateConfig(String key, String value) {
        Long count = configMapper.selectCount(new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        if (count == 0) {
            addConfig(key, value);
            return;
        }
        Config config = new Config();
        config.setKey(key);
        config.setValue(value);
        config.setUpdateTime(System.currentTimeMillis());
        configMapper.update(config,new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        configMap.put(key, value);
        log.info("更新数据库中的配置完成，key: {}, value: {}", key, value);
    }
    /**
     * 删除数据库中的配置
     * @param key 配置键
     */
    public void deleteConfig(String key) {
        configMapper.delete(new LambdaQueryWrapper<Config>().eq(Config::getKey, key));
        configMap.remove(key);
        log.info("删除数据库中的配置完成，key: {}", key);
    }
    /**
     * 添加数据库中的配置
     * @param key 配置键
     * @param value 配置值
     */
    private void addConfig(String key, String value) {
        Config config = new Config();
        config.setKey(key);
        config.setValue(value);
        configMapper.insert(config);
        configMap.put(key, value);
        log.info("添加数据库中的配置完成，key: {}, value: {}", key, value);
    }


}
