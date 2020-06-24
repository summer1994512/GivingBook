package com.miaoshaproject.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.miaoshaproject.serializer.JodaDateTimeJsonDeserializer;
import com.miaoshaproject.serializer.JodaDateTimeJsonSerializer;
import org.joda.time.DateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.stereotype.Component;

@Component
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class RedisConfig {

    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        RedisTemplate redisTemplate = new RedisTemplate();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        //首先解决key的序列化方式
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        redisTemplate.setKeySerializer(stringRedisSerializer);

        //解决value的序列化方式
        Jackson2JsonRedisSerializer jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer(Object.class);

        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule simpleModule = new SimpleModule();
        //使用自定义类将JodaDateTime类给转换成字符串
        simpleModule.addSerializer(DateTime.class,new JodaDateTimeJsonSerializer());
        //使用自定义类将字符串给逆转换成JodaDateTime类
        simpleModule.addDeserializer(DateTime.class,new JodaDateTimeJsonDeserializer());

        //默认redis没有记录存放对象的数据类型，默认是object类所以每次在使用redistemplate获取类的value时要对应强转
        //所以优化redis记录对象的数据类型，不用强转
        objectMapper.enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);

        //注册
        objectMapper.registerModule(simpleModule);
        //绑定
        jackson2JsonRedisSerializer.setObjectMapper(objectMapper);

        redisTemplate.setValueSerializer(jackson2JsonRedisSerializer);

        return redisTemplate;
    }
}
