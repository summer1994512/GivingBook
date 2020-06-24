package com.miaoshaproject.error;

public interface CommonError {
    public int getErrcode();
    public String getErrMsg();
    public CommonError setErrMsg(String errMsg);
}
