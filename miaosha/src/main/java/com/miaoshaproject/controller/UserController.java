package com.miaoshaproject.controller;

import com.miaoshaproject.controller.viewobject.UserVO;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.UserService;
import com.miaoshaproject.service.model.UserModel;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import sun.misc.BASE64Encoder;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Controller("user")
@RequestMapping(value = "/user")
//@CrossOrigin设置跨域请求准许通过，DEFAULT_ALLOWED_HEADERS允许跨域传输所有的header参数，
// 将用于token放入header域做session共享的跨域请求，DEFAULT_ALLOW_CREDENTIALS=true需要
// 前端ajax请求内设置xhrFields授信后使得跨域session共享
@CrossOrigin(allowCredentials="true", allowedHeaders="*")
public class UserController extends BaseController{

    @Autowired
    private UserService userService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    //用户登陆接口
    @RequestMapping(value = "/login",method = {RequestMethod.POST},consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType login(@RequestParam(name = "telphone")String telphone,
                                  @RequestParam(name = "password")String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //入参校验
        if (StringUtils.isEmpty(telphone)||StringUtils.isEmpty(password)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
        }

        //用户登陆服务用来校验用户登录是否合法
        UserModel userModel = userService.validateLogin(telphone, this.EncodeByMd5(password));

        //将登陆凭证加入到用户登陆成功的session内
//        this.httpServletRequest.getSession().setAttribute("LOGIN",true);
//        this.httpServletRequest.getSession().setAttribute("LOGIN_USER",userModel);
//        return CommonReturnType.create(null);

        //将登陆凭证加入到用户登陆成功的session内，修改成若用户登陆验证成功后将对应的登陆信息和登陆凭证一起存入redis中
        //生成登陆凭证的token,uuid，保证每个用户的登陆凭证不一样
        String uuidToken = UUID.randomUUID().toString().replace("-","");

        //建立用户token和登陆态之间的联系，设定redis中uuidToken存在时间为1小时
        redisTemplate.opsForValue().set(uuidToken,userModel);
        redisTemplate.expire(uuidToken,1, TimeUnit.HOURS);

        //下发了token
        return CommonReturnType.create(uuidToken);

    }

    //用户注册接口
    @RequestMapping(value = "/register",method = {RequestMethod.POST},consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType register(@RequestParam(name = "telphone")String telphone,
                                     @RequestParam(name = "otpCode")String otpCode,
                                     @RequestParam(name = "name")String name,
                                     @RequestParam(name = "gender")Integer gender,
                                     @RequestParam(name = "age")Integer age,
                                     @RequestParam(name = "password") String password) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        //验证手机号和对应的otpCode相符合
        String inSessionOtpCode = (String) this.httpServletRequest.getSession().getAttribute(telphone);
        if (!com.alibaba.druid.util.StringUtils.equals(otpCode,inSessionOtpCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"短信验证码不符合");
        }

        //用户注册流程
        UserModel userModel = new UserModel();
        userModel.setName(name);
        userModel.setTelphone(telphone);
        userModel.setGender(new Byte(String.valueOf(gender)));
//        userModel.setGender(new Byte(String.valueOf(gender.intValue())));
        userModel.setAge(age);
        userModel.setRegisterMode("byphone");
        //明文密码传进来，要进行加密，存入数据库
        userModel.setEncrptPassword(this.EncodeByMd5(password));

        userService.register(userModel);
        return CommonReturnType.create(null);
    }
    //明文密码传进来，要进行加密，存入数据库
    public String EncodeByMd5(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        //确定计算方法
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        BASE64Encoder base64Encoder = new BASE64Encoder();
        //加密字符串
        String newstr = base64Encoder.encode(md5.digest(str.getBytes("utf-8")));
        return newstr;
    }

    //获取用户otp短信接口
    @RequestMapping(value = "/getotp",method = {RequestMethod.POST},consumes ={CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType getotp(@RequestParam(name = "telphone") String telphone){
        //需要按照一定的规则生成otp验证码
        Random random = new Random();
        int randomInt = random.nextInt(99999);
        randomInt+=10000;
        String otpCode = String.valueOf(randomInt);

        //将otp验证码同用户的手机号关联，项目中使用redis，暂时先使用Httpsession的方式绑定他的手机号和optCode
        httpServletRequest.getSession().setAttribute(telphone,otpCode);

        //将otp验证码通过短信发送给用户，省略
        System.out.println("telphone="+telphone+"&otpCode="+otpCode);

        return CommonReturnType.create(null);
    }
    @RequestMapping(value = "/get")
    @ResponseBody
    public CommonReturnType getUser(@RequestParam(name="id") Integer id) throws BusinessException {
        //调用service服务获取对应id的用户对象并返回给前段
        UserModel userModel = userService.getUserById(id);

        //若获取的对应用户信息不存在
        if (userModel==null){
//            userModel.setEncrptPassword("123");
            throw new BusinessException(EmBusinessError.USER_NOT_EXIST);
        }
        //将核心领域模型用户对象转化为可供UI使用的ViewObject
        UserVO userVO = convertFromModel(userModel);

        //返回通用对象
        return CommonReturnType.create(userVO);
    }

    public UserVO convertFromModel(UserModel userModel){
        if (userModel==null){
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(userModel,userVO);
        return userVO;
    }
}
