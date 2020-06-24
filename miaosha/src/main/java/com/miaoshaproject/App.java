package com.miaoshaproject;

import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.miaoshaproject.dao.UserDOMapper;
import com.miaoshaproject.dataobject.UserDO;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Scanner;

/**
 * Hello world!
 */

//@EnableAutoConfiguration//开启工程基于springboot自动化的配置，会启动一个内嵌的tomcat
@SpringBootApplication(scanBasePackages = {"com.miaoshaproject"})//开启工程基于springboot自动化的配置
@RestController//开启springmvc的控制器
@MapperScan("com.miaoshaproject.dao")
public class App {

    @Autowired
    private UserDOMapper userDOMapper;

    @RequestMapping("/")
    public String home(){
        UserDO userDO = userDOMapper.selectByPrimaryKey(1);
        if (userDO==null){
            return "用户对象不存在";
        }else {
            return userDO.getName();
        }
    }
    public static void main(String[] args) {
        System.out.println("Hello World!");
        SpringApplication.run(App.class,args);//添加注解以后要开启

    }
}
