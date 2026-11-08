package com.acme.discovery;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"eureka.server.enable-self-preservation=false"})
@AutoConfigureMockMvc
class DiscoveryIntegrationTest {
  @Autowired private MockMvc mvc;

  @Test
  void registryHealthIsAvailable() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void dashboardLoadsWithoutAuthenticationInLocalMode() throws Exception {
    mvc.perform(get("/").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
  }

  @Test
  void unknownEndpointReturnsNotFound() throws Exception {
    mvc.perform(get("/missing-endpoint")).andExpect(status().isNotFound());
  }
}
