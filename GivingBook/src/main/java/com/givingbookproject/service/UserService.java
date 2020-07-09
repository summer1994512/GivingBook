package com.givingbookproject.service;

import com.givingbookproject.service.model.UserModel;
import com.givingbookproject.error.BusinessException;

public interface UserService {
    //通过用户Id获取用户对象的方法
    UserModel getUserById(Integer id);

    //当验证码验证成功，处理注册用户信息的方法
    void register(UserModel userModel) throws BusinessException;

    /**
     * 校验用户登陆服务是否合法
     * @param telphone 用户注册手机
     * @param encrptPassword 用户加密后的密码
     * @throws BusinessException
     */
    UserModel validateLogin(String telphone,String encrptPassword) throws BusinessException;

    UserModel getUserByIdInCache(Integer id);
}
