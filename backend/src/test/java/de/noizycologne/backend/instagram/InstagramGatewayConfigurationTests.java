package de.noizycologne.backend.instagram;

import de.noizycologne.backend.instagram.gateway.FailClosedInstagramGateway;
import de.noizycologne.backend.instagram.gateway.GraphApiInstagramGateway;
import de.noizycologne.backend.instagram.gateway.InstagramGatewayConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Gateway-Konfiguration muss ohne einen externen {@code RestClient.Builder}-
 * Bean starten. Es wird keine Datenbank und kein Netzwerk benötigt.
 */
class InstagramGatewayConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InstagramGatewayConfiguration.class);

    @Test
    void enabledGatewayWithoutTokenBootsExactlyOneFailClosedBean() {
        runner.withPropertyValues(
                        "noizy.instagram.gateway.enabled=true",
                        "noizy.instagram.access-token=")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(InstagramGateway.class)).hasSize(1);
                    assertThat(context.getBean(InstagramGateway.class))
                            .isInstanceOf(FailClosedInstagramGateway.class);
                    assertThat(context.getBeansOfType(RestClient.Builder.class)).isEmpty();

                    InstagramGateway gateway = context.getBean(InstagramGateway.class);
                    IllegalStateException thrown;
                    try {
                        gateway.publishContainer(new InstagramAccount("test-account"), "container-1");
                        throw new AssertionError("fail-closed gateway darf nicht veröffentlichen");
                    } catch (IllegalStateException exception) {
                        thrown = exception;
                    }
                    assertThat(thrown).hasMessageContaining("nicht freigegeben");
                });
    }

    @Test
    void enabledGatewayWithTokenBuildsGraphClientWithoutExternalBuilder() {
        runner.withPropertyValues(
                        "noizy.instagram.gateway.enabled=true",
                        "noizy.instagram.access-token=fake-test-token-not-a-real-secret",
                        "noizy.instagram.gateway.connect-timeout-ms=1500",
                        "noizy.instagram.gateway.read-timeout-ms=2500")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(InstagramGateway.class)).hasSize(1);
                    assertThat(context.getBean(InstagramGateway.class))
                            .isInstanceOf(GraphApiInstagramGateway.class);
                    assertThat(context.getBeansOfType(RestClient.Builder.class)).isEmpty();
                });
    }

    @Test
    void disabledGatewayStaysFailClosed() {
        runner.withPropertyValues("noizy.instagram.gateway.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(InstagramGateway.class)).hasSize(1);
                    assertThat(context.getBean(InstagramGateway.class))
                            .isInstanceOf(FailClosedInstagramGateway.class);
                });
    }
}
