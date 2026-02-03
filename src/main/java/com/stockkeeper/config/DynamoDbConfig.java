package com.stockkeeper.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Creates the AWS SDK v2 low-level {@link DynamoDbClient} bean.
 *
 * Key design decisions:
 * - Uses endpoint override so the same code works against DynamoDB Local
 *   (Docker) and real AWS with just a config change.
 * - Uses StaticCredentialsProvider with dummy creds for local dev.
 *   In production, swap to DefaultCredentialsProvider or IAM roles.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DynamoDbConfig {

    private final AwsDynamoDbProperties awsProperties;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        String endpoint = awsProperties.getDynamodb().getEndpoint();
        String region = awsProperties.getDynamodb().getRegion();

        log.info("Configuring DynamoDbClient — endpoint={}, region={}", endpoint, region);

        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        awsProperties.getCredentials().getAccessKey(),
                                        awsProperties.getCredentials().getSecretKey()
                                )
                        )
                )
                .build();
    }
}
