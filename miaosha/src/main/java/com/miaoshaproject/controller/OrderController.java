package com.miaoshaproject.controller;

import com.google.common.util.concurrent.RateLimiter;
import com.miaoshaproject.error.BusinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.mq.MqProducer;
import com.miaoshaproject.response.CommonReturnType;
import com.miaoshaproject.service.CacheService;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.OrderService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.model.ItemModel;
import com.miaoshaproject.service.model.OrderModel;
import com.miaoshaproject.service.model.UserModel;
import com.miaoshaproject.util.CodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.RenderedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.*;

@Controller("order")
@RequestMapping("/order")
//@CrossOrigin设置跨域请求准许通过，DEFAULT_ALLOWED_HEADERS允许跨域传输所有的header参数，
// 将用于token放入header域做session共享的跨域请求，DEFAULT_ALLOW_CREDENTIALS=true需要
// 前端ajax请求内设置xhrFields授信后使得跨域session共享
@CrossOrigin(allowCredentials = "true", allowedHeaders = "*")
public class OrderController extends BaseController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ItemService itemService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private PromoService promoService;

    private ExecutorService executorService;

    private RateLimiter orderCreateRateLimiter;

    @PostConstruct
    public void init() {
        //开一个20个线程的线程池，此处线程加入了队列
        executorService = Executors.newFixedThreadPool(20);

        //使用google 的guava的RateLimite来做限流操作，一台服务器的tps设为300，超过300则拒绝访问服务器（从controller层做一个防刷）。
        orderCreateRateLimiter = RateLimiter.create(300);
    }

    //生成验证码
    @RequestMapping(value = "/generateverifycode", method = {RequestMethod.POST,RequestMethod.GET})
    @ResponseBody
    public void generateverifycode(HttpServletResponse response) throws BusinessException, IOException {
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆，不能生成验证码");
        }

        //获取用户的登陆信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null) {
            //说明登陆过期，时间超时
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆,不能生成验证码");
        }


        Map<String,Object> map = CodeUtil.generateCodeAndPic();

        //将验证码和用户的id做一次绑定
        redisTemplate.opsForValue().set("verify_code_"+userModel.getId(),map.get("code"));
        redisTemplate.expire("verify_code_"+userModel.getId(),10,TimeUnit.MINUTES);

        ImageIO.write((RenderedImage) map.get("codePic"), "jpeg", response.getOutputStream());
    }

    //生成秒杀令牌
    @RequestMapping(value = "/generatetoken", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType generatetoken(@RequestParam(name = "itemId") Integer itemId,
                                          @RequestParam(name = "promoId") Integer promoId,
                                          @RequestParam(name = "verifyCode") String verifyCode) throws BusinessException {

        //获取前端url传来的token，页可以在方法参数中加@RequestParam(name = "token",required = false)Integer token来获取
        //根据token获取用户信息
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆不能下单");
        }
        //获取用户的登陆信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null) {
            //说明登陆过期，时间超时
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆不能下单");
        }

        //通过verifycode验证验证码的有效性
        String redisVerifyCode = (String) redisTemplate.opsForValue().get("verify_code_" + userModel.getId());
        if (StringUtils.isEmpty(redisVerifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "请求非法");
        }
        if (!redisVerifyCode.equalsIgnoreCase(verifyCode)){
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "请求非法，验证码错误");
        }


        //获取秒杀访问令牌
        String promoToken = promoService.generateSecondKillToken(promoId, itemId, userModel.getId());

        if (promoToken == null) {
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "生成令牌失败");
        }

        //返回对应的结果
        return CommonReturnType.create(promoToken);

    }


    //封装下单请求
    @RequestMapping(value = "/createorder", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType createOrder(@RequestParam(name = "itemId") Integer itemId,
                                        @RequestParam(name = "amount") Integer amount,
                                        @RequestParam(name = "promoId", required = false) Integer promoId,
                                        @RequestParam(name = "promoToken", required = false) String promoToken) throws BusinessException {
        //required默认为true：必须要传入该值，否则报错。但是此处true/false不影响，因为下单过程中必定传入该值，如果有活动则为promoId，没活动则为null;

        //修改前：cookie 使用tomcat容器将 session存储到redis的方式
//        Boolean isLogin = (Boolean) httpServletRequest.getSession().getAttribute("LOGIN");
//        if (isLogin==null||!isLogin.booleanValue()){
//            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN);
//        }
//
////        System.out.println(promoId+"*******");
//        //获取用户登陆信息
//        UserModel userModel = (UserModel) httpServletRequest.getSession().getAttribute("LOGIN_USER");
//
        //令牌桶算法获取令牌：如果获取到了返回true，否则返回false
        if (!orderCreateRateLimiter.tryAcquire()){
            throw new BusinessException(EmBusinessError.RATELIMIT);
        }
        //修改后：token 使用java代码将session存储redis的方式
        //获取前端url传来的token(登陆信息)，页可以在方法参数中加@RequestParam(name = "token",required = false)Integer token来获取
        String token = httpServletRequest.getParameterMap().get("token")[0];
        if (StringUtils.isEmpty(token)) {
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆不能下单");
        }
        //获取用户的登陆信息
        UserModel userModel = (UserModel) redisTemplate.opsForValue().get(token);
        if (userModel == null) {
            //说明登陆过期，时间超时
            throw new BusinessException(EmBusinessError.USER_NOT_LOGIN, "用户还未登陆不能下单");
        }

        //校验秒杀令牌是否存在
        if (promoToken != null) {
            String inRedisPromoToken = (String) redisTemplate.opsForValue().get("promo_token_" + promoId + "_userId_" + userModel.getId() + "_itemId_" + itemId);
            if (inRedisPromoToken == null) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }
            if (!StringUtils.equals(promoToken, inRedisPromoToken)) {
                throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "秒杀令牌校验失败");
            }






        }


        //此处增加队列泄洪的代码减小并发压力，保护下游生成订单
        //同步调用线程池的submit方法
        //拥塞窗口为20的等待队列，用来队列化泄洪，在一台服务器同一时刻只有最多20个用户请求来下单，其他的请求要排队。
        Future<Object> future = executorService.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {

                //加入库存流水init状态
                String stockLogId = itemService.initStockLog(itemId, amount);


                //再去完成对应的下单事物型消息机制
                if (!mqProducer.transactionAsyncReduceStock(userModel.getId(), promoId, itemId, amount, stockLogId)) {
                    throw new BusinessException(EmBusinessError.UNKNOWN_ERROR, "下单失败");
                }

                updateCachePropertires(itemId);

                return null;
            }
        });

        try {
            //等待future对象执行完成
            future.get();
        } catch (InterruptedException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        } catch (ExecutionException e) {
            throw new BusinessException(EmBusinessError.UNKNOWN_ERROR);
        }

//        OrderModel orderModel = orderService.createOrder(userModel.getId(), itemId, promoId, amount);


//        //从缓存中更新页面对应的库存和销量信息
//        this.updateCachePropertires(itemId);

//        ItemModel itemModel = null;
//        itemModel = (ItemModel) cacheService.getFromCommonCache("item_" + itemId);
//        if (itemModel != null) {
//            itemModel.setStock(itemModel.getStock() + (amount * -1));
//            cacheService.setCommonCache("item_" + itemId, itemModel);
//        }
//        itemModel = (ItemModel) redisTemplate.opsForValue().get("item_" + itemId);
//        if (itemModel != null) {
//            itemModel.setStock(itemModel.getStock() + (amount * -1));
//            redisTemplate.opsForValue().set("item_" + itemId,itemModel);
//        }
        return CommonReturnType.create(null);
    }

    public void updateCachePropertires(Integer itemId) {
        ItemModel itemModel = (ItemModel) cacheService.getFromCommonCache("item_" + itemId);
        Integer stock = (Integer) redisTemplate.opsForValue().get("promo_item_stock_" + itemId);
        Integer sales = (Integer) redisTemplate.opsForValue().get("promo_item_sales_" + itemId);
        if (itemModel != null && stock != null && sales != null) {
            itemModel.setStock(stock);
            itemModel.setSales(sales);
            cacheService.setCommonCache("item_" + itemId, itemModel);
        }

        itemModel = (ItemModel) redisTemplate.opsForValue().get("item_" + itemId);
        if (itemModel != null && stock != null && sales != null) {
            itemModel.setStock(stock);
            itemModel.setSales(sales);
            redisTemplate.opsForValue().set("item_" + itemId, itemModel);
        }
    }

}
