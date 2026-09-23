package vn.techies.ecommerce.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import vn.techies.ecommerce.gateway.config.GatewayProperties;


@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)

public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
