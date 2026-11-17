package com.property.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 物业 AI 智能客服 · Java BFF/领域服务入口。
 *
 * <p>P1 阶段采用“模块化单体”：bff 负责对前端的 REST/SSE 契约，
 * domain 负责领域能力；后续再按域拆分，不按表拆。</p>
 */
@SpringBootApplication
public class PropertyAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PropertyAiApplication.class, args);
    }
}
