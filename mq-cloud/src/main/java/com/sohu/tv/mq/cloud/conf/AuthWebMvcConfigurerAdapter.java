package com.sohu.tv.mq.cloud.conf;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import com.sohu.tv.mq.cloud.web.interceptor.AdminInterceptor;
import com.sohu.tv.mq.cloud.web.interceptor.AuthInterceptor;
import com.sohu.tv.mq.cloud.web.interceptor.UserGuideInterceptor;
import com.sohu.tv.mq.cloud.web.service.UserInfoMethodArgumentResolver;

/**
 * 拦截器配置
 * @Description: 
 * @author yongfeigao
 * @date 2018年6月12日
 */
@Configuration
public class AuthWebMvcConfigurerAdapter extends WebMvcConfigurerAdapter {
    
    @Autowired
    @Qualifier("authInterceptor")
    private AuthInterceptor authInterceptor;
    
    @Autowired
    @Qualifier("userGuideInterceptor")
    private UserGuideInterceptor userGuideInterceptor;
    
    @Autowired
    @Qualifier("adminInterceptor")
    private AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用户登录拦截器
        registry.addInterceptor(authInterceptor).excludePathPatterns("/error", "/admin/**", "/user/guide/**",
                "/cluster/**", "/register/**", "/login/**", "/rocketmq/**");
        // 用户引导拦截器
        registry.addInterceptor(userGuideInterceptor).addPathPatterns("/user/guide/**");
        // admin模块拦截器
        registry.addInterceptor(adminInterceptor).addPathPatterns("/admin/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(uerInfoMethodArgumentResolver());
        super.addArgumentResolvers(argumentResolvers);
    }

    @Bean
    public UserInfoMethodArgumentResolver uerInfoMethodArgumentResolver() {
        return new UserInfoMethodArgumentResolver();
    }
}
