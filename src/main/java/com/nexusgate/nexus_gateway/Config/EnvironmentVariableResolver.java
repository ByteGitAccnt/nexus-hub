package com.nexusgate.nexus_gateway.Config;


import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EnvironmentVariableResolver {
//replace all the string/values that match with starting $ with actual env values from the system
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    public String resolve(String content) {

        Matcher matcher = ENV_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1);

            String environmentValue = System.getenv(variableName);
            if (environmentValue == null || environmentValue.isBlank()) {
                throw new IllegalStateException(
                        "Required environment variable is not set: " + variableName
                );
            }

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(environmentValue)
            );
        }

        matcher.appendTail(result);

        return result.toString();
    }
}
