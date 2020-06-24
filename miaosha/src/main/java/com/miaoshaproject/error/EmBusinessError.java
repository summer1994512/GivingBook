package com.miaoshaproject.error;

public enum EmBusinessError implements CommonError {
    //通用错误类型10001
    PARAMETER_VALIDATION_ERROR(10001,"参数不合法"),
    UNKNOWN_ERROR(10002,"未知错误"),


    //20000开头为用户信息相关错误定义
    USER_NOT_EXIST(20001,"用户不存在"),
    USER_LOGIN_FAIL(20002,"用户手机号或者密码不正确"),
    USER_NOT_LOGIN(20003,"用户还未登陆"),

    //30000开头为产品信息相关错误定义
    ITEM_CREATE_FAIL(30001,"产品已经创建，不能重复创建"),

    //40000开头为交易信息错误
    STOCK_NOT_ENOUGH(40001,"库存不足"),
    MQ_SEND_FAIL(40002,"库存异步消息失败"),
    RATELIMIT(40003,"活动太火爆，请稍后再试～");
    ;


    EmBusinessError(int errCode, String errMsg) {
        this.errCode = errCode;
        this.errMsg = errMsg;
    }

    private int errCode;
    private String errMsg;


    @Override
    public int getErrcode() {
        return this.errCode;
    }

    @Override
    public String getErrMsg() {
        return this.errMsg;
    }

    @Override
    public CommonError setErrMsg(String errMsg) {
        this.errMsg=errMsg;
        return this;
    }
}
