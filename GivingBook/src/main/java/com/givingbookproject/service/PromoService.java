package com.givingbookproject.service;

import com.givingbookproject.service.model.PromoModel;

//秒杀活动
public interface PromoService {

    //根据itemId获取即将进行的或者正在进行的秒杀活动信息
    PromoModel getPromoByItemId(Integer itemId);

    //活动发布
    void publishPromo(Integer promoId);

    //生成秒杀用的令牌
    String generateSecondKillToken(Integer promoId,Integer itemId,Integer userId);
}
