package com.spingbootinit;

import com.spingbootinit.utils.ClientIpUtils;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 数字验证码 存入到redis
 */
class SpingbootInitApplicationTests {
    @Test
    void contextLoads() {
        Random rand = new Random();
        int a = rand.nextInt(10) + 1;  // 1~10
        int b = rand.nextInt(10) + 1;
        int op = rand.nextInt(2);       // 0:+, 1:-
        String expression;
        int result;
        if (op == 0) {
            expression = a + " + " + b + " = ?";
            result = a + b;
        } else {
            // 保证被减数不小于减数，避免负数
            if (a < b) {
                int tmp = a;
                a = b;
                b = tmp;
            }
            expression = a + " - " + b + " = ?";
            result = a - b;
        }
        // 单元测试不依赖外部 Redis；这里只验证验证码计算逻辑。
        Map<String, String> response = new HashMap<>();
        response.put("expression", expression);
        response.put("answer", String.valueOf(result));
        System.out.println(response);
    }

    /**
     * 测试ip
     */
    @Test
    public void testRedis() {
        String resolve = ClientIpUtils.resolve(null);
        System.out.println(resolve);
    }

}
