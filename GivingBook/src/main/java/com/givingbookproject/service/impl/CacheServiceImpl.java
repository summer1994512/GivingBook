package com.givingbookproject.service.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.givingbookproject.service.CacheService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Service
public class CacheServiceImpl implements CacheService {

    //Cache由google guava提供，类似hashMap但是可以设置初始容量和过期时间过期策略
    private Cache<String,Object> commonCache = null;

    //该注解表示在次bean被加载的时候优先执行该注解标注的方法
    @PostConstruct
    public void init(){
        commonCache = CacheBuilder.newBuilder()
                //设置缓存容器的初始容量为10
                .initialCapacity(10)
                //设置缓存中最大可以存储100哥KEY，超过100个后会按照LRU的策略移除缓存项
                .maximumSize(100)
                //设置写缓存后多少秒过期60秒
                .expireAfterWrite(60, TimeUnit.SECONDS).build();
    }

    @Override
    public void setCommonCache(String key, Object value) {
        commonCache.put(key,value);
    }

    @Override
    public Object getFromCommonCache(String key) {
        //如果存在，则返回取出的值，如果不存在则返回null
        return commonCache.getIfPresent(key);
    }
}
