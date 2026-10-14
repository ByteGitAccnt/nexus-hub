package com.nexusgate.nexus_gateway.Config.RateLimit;

import org.springframework.stereotype.Component;

@Component
public class RateLimitKeyGenerator {
    // this is key is our redis data design, we will use this prefix to store the rate limit data in redis
    // hence we created this specific class for generating the key
    public static final String IP_PREFIX = "nexusgate:ratelimit:ip:";
    public static final String USER_PREFIX = "nexusgate:ratelimit:user:";

    public String forIp(String ip){
        return IP_PREFIX + ip;
    }

    public String forUser(String userId) {
        return USER_PREFIX + userId;
    }
}
