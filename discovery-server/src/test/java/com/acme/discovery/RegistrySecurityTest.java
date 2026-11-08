package com.acme.discovery;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "registry.security.enabled=true",
      "registry.security.username=registry-test",
      "registry.security.password=registry-test-password",
      "eureka.server.enable-self-preservation=false"
    })
@AutoConfigureMockMvc
class RegistrySecurityTest {
  @Autowired private MockMvc mvc;

  @Test
  void registryRequiresCredentialsWhenEnabled() throws Exception {
    mvc.perform(get("/")).andExpect(status().isUnauthorized());
  }

  @Test
  void registryAcceptsConfiguredCredentials() throws Exception {
    mvc.perform(get("/").with(httpBasic("registry-test", "registry-test-password")))
        .andExpect(status().isOk());
  }

  @Test
  void registryRejectsIncorrectPassword() throws Exception {
    mvc.perform(get("/").with(httpBasic("registry-test", "incorrect-password")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void healthIsPublicWithSecurityEnabled() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
