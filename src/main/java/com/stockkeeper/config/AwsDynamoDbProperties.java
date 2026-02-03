package com.stockkeeper.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the aws.* block from application.yml into a strongly-typed POJO.
 *
 * Example YAML:
 *   aws:
 *     dynamodb:
 *       endpoint: http://localhost:8000
 *       region: us-east-1
 *     credentials:
 *       access-key: fakeAccessKey
 *       secret-key: fakeSecretKey
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aws")
public class AwsDynamoDbProperties {

    private DynamoDb dynamodb = new DynamoDb();
    private Credentials credentials = new Credentials();

    @Getter
    @Setter
    public static class DynamoDb {
        private String endpoint;
        private String region;
    }

    @Getter
    @Setter
    public static class Credentials {
        private String accessKey;
        private String secretKey;
    }
}
