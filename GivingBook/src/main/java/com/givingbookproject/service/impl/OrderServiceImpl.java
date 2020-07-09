package com.givingbookproject.service.impl;

import com.givingbookproject.dataobject.OrderDO;
import com.givingbookproject.dataobject.SequenceDO;
import com.givingbookproject.dataobject.StockLogDO;
import com.givingbookproject.dao.OrderDOMapper;
import com.givingbookproject.dao.SequenceDOMapper;
import com.givingbookproject.dao.StockLogDOMapper;
import com.givingbookproject.error.BusinessException;
import com.givingbookproject.error.EmBusinessError;
import com.givingbookproject.service.ItemService;
import com.givingbookproject.service.OrderService;
import com.givingbookproject.service.UserService;
import com.givingbookproject.service.model.ItemModel;
import com.givingbookproject.service.model.OrderModel;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private ItemService itemService;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderDOMapper orderDOMapper;

    @Autowired
    private SequenceDOMapper sequenceDOMapper;

    @Autowired
    private StockLogDOMapper stockLogDOMapper;



    @Override
    @Transactional
    public OrderModel createOrder(Integer userId, Integer itemId, Integer promoId, Integer amount,String stockLogId) throws BusinessException {


//        将验证用户信息和商品失信是否合法，迁移到生成活动秒杀令牌内完成

//        //1、校验下单状态，下单的商品是否存在，用户是否合法，购买数量是否正确，校验活动信息
////        ItemModel itemModel = itemService.getItemById(itemId);
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);
        if (itemModel == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "商品信息不存在");
        }
////        UserModel userModel = userService.getUserById(userId);
//        UserModel userModel = userService.getUserByIdInCache(userId);
//        if (userModel == null) {
//            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "用户信息不存在");
//        }

        if (amount <= 0 || amount > 99) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "数量信息不正确");
        }

        //校验活动信息
//        if (promoId != null) {
//            //(1)校验对应活动是否存在这个适用商品
//            if (promoId.intValue() != itemModel.getPromoModel().getId()) {
//                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "活动信息不正确");
//                //(2)校验活动是否正在进行中
//            } else if (itemModel.getPromoModel().getStatus().intValue() != 2) {
//                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "活动还没开始");
//            }
//        }

        //2、落单减库存 电商减库存有两种：1、落单减库存 2、支付减库存
        boolean result = itemService.decreaseStock(itemId, amount);
        if (!result) {
            throw new BusinessException(EmBusinessError.STOCK_NOT_ENOUGH);
        }
        //3、订单入库
        OrderModel orderModel = new OrderModel();
        orderModel.setUserId(userId);
        orderModel.setItemId(itemId);
        orderModel.setAmount(amount);
        if (promoId != null) {
            orderModel.setItemPrice(itemModel.getPromoModel().getPromoItemPrice());
        } else {
            orderModel.setItemPrice(itemModel.getPrice());
        }
        orderModel.setOrderPrice(orderModel.getItemPrice().multiply(new BigDecimal(amount)));
        orderModel.setPromoId(promoId);

        //生成交易流水号，订单号
        orderModel.setId(generateOrderNo());
        OrderDO orderDO = convertFromOrderModel(orderModel);
        orderDOMapper.insertSelective(orderDO);

        //加上商品的销量
        itemService.increaseSales(itemId, amount);

        //设置库存流水状态为成功
        StockLogDO stockLogDO = stockLogDOMapper.selectByPrimaryKey(stockLogId);
        if (stockLogDO==null){
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }
        stockLogDO.setStatus(2);
        stockLogDOMapper.updateByPrimaryKeySelective(stockLogDO);



        //springBoot的Transaction标签提供了一个功能：在最近的一个事物commit以后在执行下一步操作
        //用这个功能来更新数据库库存，可以避免发送完减库存的消息以后，事物在提交的时候出了错，导致库存白白浪费
//        TransactionSynchronizationManager.registerSynchronization(
//                new TransactionSynchronizationAdapter() {
//                    @Override
//                    public void afterCommit() {
//                        //异步更新库存
//                        boolean mqResult = itemService.asyncDecreaseStock(itemId, amount);
////                        if (!mqResult) {
////                            itemService.increaseStock(itemId, amount);
////                            throw new BusinessException(EmBusinessError.MQ_SEND_FAIL);
////                        }
//                    }\
//                });

        //4、返回前端
        return orderModel;
    }

    //测试
//    public static void main(String[] args) {
//        Date date = new Date();
//        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
//        String format1 = format.format(date);
//        System.out.println(format1);
//        LocalDateTime now = LocalDateTime.now();
//        String format2 = now.format(DateTimeFormatter.ISO_DATE);
//        System.out.println(now);
//        System.out.println(format2);
//    }


    //在该方法上设置@Transactional(propagation = Propagation.REQUIRES_NEW)
    //在创建订单产生事物回滚的时候保证数据库生成订单号不会回滚，保证唯一性
    //@Transactional放在private方法上，不会报错但是不会生效，要改为public
    @Transactional(propagation = Propagation.REQUIRES_NEW)
//        private String generateOrderNo(){
    public String generateOrderNo() {
        //订单号有16位
        StringBuilder sb = new StringBuilder();
        //前8位为时间信息，年月日
        LocalDateTime now = LocalDateTime.now();
        String nowDate = now.format(DateTimeFormatter.ISO_DATE).replace("-", "");
        sb.append(nowDate);

        //中间6位为自增序列
        //获取当前sequence
        int sequence = 0;
        SequenceDO sequenceDO = sequenceDOMapper.getSequenceByName("order_info");
        sequence = sequenceDO.getCurrentValue();
        sequenceDO.setCurrentValue(sequenceDO.getCurrentValue() + sequenceDO.getStep());
        sequenceDOMapper.updateByPrimaryKeySelective(sequenceDO);
        String str = String.valueOf(sequence);
        for (int i = 0; i < 6 - str.length(); i++) {
            sb.append(0);
        }
        sb.append(str);

        //最后2位为分库分表位,暂时写死
        sb.append("00");
        return sb.toString();
    }

    private OrderDO convertFromOrderModel(OrderModel orderModel) {
        if (orderModel == null) {
            return null;
        }

        OrderDO orderDO = new OrderDO();
        BeanUtils.copyProperties(orderModel, orderDO);
        orderDO.setItemPrice(orderModel.getItemPrice().doubleValue());
        orderDO.setOrderPrice(orderModel.getOrderPrice().doubleValue());
        return orderDO;
    }
}
