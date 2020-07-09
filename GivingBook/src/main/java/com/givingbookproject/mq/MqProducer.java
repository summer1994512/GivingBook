package com.givingbookproject.mq;

import com.alibaba.fastjson.JSON;
import com.givingbookproject.dao.StockLogDOMapper;
import com.givingbookproject.dataobject.StockLogDO;
import com.givingbookproject.error.BusinessException;
import com.givingbookproject.service.OrderService;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

@Component
public class MqProducer {

    private DefaultMQProducer producer;

    @Autowired
    private OrderService orderService;


    private TransactionMQProducer transactionMQProducer;

    @Value("${mq.nameserver.addr}") //注入application.properties配置文件中的值
    private String nameAddr;

    @Value("${mq.topicname}")
    private String topicName;

    @Autowired
    private StockLogDOMapper stockLogDOMapper;

    @PostConstruct //该注解表示会在此bean初始化完成后执行
    public void init() throws MQClientException {
        //做mq producer的初始化，
        // （括号内指明了该producer的group的名字，对于producer来说group的名字没有实际用处，consumer端的group有用）
        producer = new DefaultMQProducer("producer_group");
        producer.setNamesrvAddr(nameAddr);
        producer.start(); //开始链接


        transactionMQProducer = new TransactionMQProducer("transaction_producer_group");
        transactionMQProducer.setNamesrvAddr(nameAddr);
        transactionMQProducer.start();

        transactionMQProducer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message message, Object args) {
                //真正要做的事，创建订单
                Integer itemId = (Integer) ((Map) args).get("itemId");
                Integer promoId = (Integer) ((Map) args).get("promoId");
                Integer userId = (Integer) ((Map) args).get("userId");
                Integer amount = (Integer) ((Map) args).get("amount");
                String stockLogId = (String) ((Map) args).get("stockLogId");

                try {
                    orderService.createOrder(userId, itemId, promoId, amount,stockLogId);
                } catch (BusinessException e) {
                    e.printStackTrace();
                    //设置库存流水状态为回滚
                    StockLogDO stockLogDO = stockLogDOMapper.selectByPrimaryKey(stockLogId);
                    stockLogDO.setStatus(3);
                    stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }

                return LocalTransactionState.COMMIT_MESSAGE;
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                //如果执行executeLocalTransaction的时候，createOrder以后程序死机了长时间没有返回值是回滚还是提交或者返回了unknow
                //此时会调用该程序
                //根据是否扣减库存成功，来判断要返回COMMIT,ROLLBACK还是继续UNKNOWN
                String jsonString = new String(msg.getBody());
                Map<String,Object> map = JSON.parseObject(jsonString, Map.class);
                Integer itemId = (Integer) map.get("itemId");
                Integer amount = (Integer) map.get("amount");
                String stockLogId = (String) map.get("stockLogId");

                StockLogDO stockLogDO = stockLogDOMapper.selectByPrimaryKey(stockLogId);
                if (stockLogDO==null){
                    return LocalTransactionState.UNKNOW;
                }
                if (stockLogDO.getStatus()==2){
                     return LocalTransactionState.COMMIT_MESSAGE;
                }else if (stockLogDO.getStatus()==1){
                    return LocalTransactionState.UNKNOW;
                }

                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });
    }

    //事务型同步库存扣减消息
    public boolean transactionAsyncReduceStock(Integer userId, Integer promoId, Integer itemId, Integer amount,String stockLogId) {
        HashMap<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("itemId", itemId);
        bodyMap.put("amount", amount);
        bodyMap.put("stockLogId",stockLogId);

        HashMap<String, Object> argsMap = new HashMap<>();
        argsMap.put("itemId", itemId);
        argsMap.put("amount", amount);
        argsMap.put("userId", userId);
        argsMap.put("promoId", promoId);
        argsMap.put("stockLogId",stockLogId);



        Message message = new Message(topicName, "increase", JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
        TransactionSendResult sendResult = null;
        try {
            //该方法sendMessageInTransaction执行以后会将消息设定为prepare的状态放入消息队列，但是不会被consumer端消费，必须等到
            //setTransactionListener中定义的方法处理完成后（处理结果为提交，若回滚则没有）消费端才会获取到
            sendResult = transactionMQProducer.sendMessageInTransaction(message, argsMap);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        }
        if (sendResult.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
            return false;
        } else if (sendResult.getLocalTransactionState() == LocalTransactionState.COMMIT_MESSAGE) {
            return true;
        } else {
            return false;
        }
    }

    //同步库存扣减消息
    public boolean asyncReduceStock(Integer itemId, Integer amount) {
        HashMap<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("itemId", itemId);
        bodyMap.put("amount", amount);

        Message message = new Message(topicName, "increase", JSON.toJSON(bodyMap).toString().getBytes(Charset.forName("UTF-8")));
        try {
            producer.send(message);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        } catch (RemotingException e) {
            e.printStackTrace();
            return false;
        } catch (MQBrokerException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
