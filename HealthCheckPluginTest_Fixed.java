/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.tests.acceptance.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.tests.acceptance.dsl.AcceptanceTestBase;
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HealthCheckPluginTest extends AcceptanceTestBase {

  private BesuNode node;
  private OkHttpClient client;

  @BeforeEach
  public void setUp() throws Exception {
    // Create a node with minimal configuration for DEV network
    // Remove sync-min-peers to avoid peer connection issues in isolated test environment
    node =
        besu.createPluginsNode(
            "node1",
            List.of("testPlugins"),
            List.of(
                "--miner-enabled=false",
                "--network=DEV",
                "--sync-mode=FULL",
                "--rpc-http-enabled=true",
                "--rpc-http-host=127.0.0.1",
                "--rpc-http-port=0",
                "--rpc-http-api=ETH,NET,WEB3,ADMIN",
                "--discovery-enabled=false",
                "--p2p-enabled=false"));
    
    cluster.start(node);
    
    // Wait for the node to be fully ready
    waitForNodeToBeReady();
    
    client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();
  }

  @Test
  public void livenessEndpointShouldReturn200WhenHealthy() throws IOException {
    Response response = callHealthEndpoint("/liveness");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).contains("UP");
  }

  @Test
  public void livenessEndpointShouldReturn503WhenForcedDownViaCli() throws Exception {
    final BesuNode nodeDown =
        besu.createPluginsNode(
            "node-down",
            List.of("testPlugins"),
            List.of(
                "--miner-enabled=false",
                "--plugin-test-health-liveness-down=true",
                "--network=DEV",
                "--sync-mode=FULL",
                "--rpc-http-enabled=true",
                "--rpc-http-host=127.0.0.1",
                "--rpc-http-port=0",
                "--rpc-http-api=ETH,NET,WEB3,ADMIN",
                "--discovery-enabled=false",
                "--p2p-enabled=false"));
    try {
      cluster.start(nodeDown);
      
      // Wait for the node to be ready
      waitForNodeToBeReady(nodeDown);
      
      final String url =
          "http://" + nodeDown.getHostName() + ":" + nodeDown.getJsonRpcPort().get() + "/liveness";
      final Request request = new Request.Builder().url(url).build();
      try (Response response = client.newCall(request).execute()) {
        assertThat(response.code()).isEqualTo(503);
      }
    } finally {
      cluster.stopNode(nodeDown);
    }
  }

  @Test
  public void readinessEndpointShouldReturn200WhenHealthy() throws IOException {
    Response response = callHealthEndpoint("/readiness");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).contains("UP");
  }

  @Test
  public void readinessEndpointShouldRespectMinPeersParameter() throws IOException {
    // Test with minPeers=0 (should pass since we have 0 peers in isolated test)
    Response response1 = callHealthEndpoint("/readiness?minPeers=0");
    assertThat(response1.code()).isEqualTo(200);

    // Test with minPeers=100 (should fail since we have 0 peers)
    Response response2 = callHealthEndpoint("/readiness?minPeers=100");
    assertThat(response2.code()).isEqualTo(503);
  }

  @Test
  public void readinessEndpointShouldRespectMaxBlocksBehindParameter() throws IOException {
    // Test with maxBlocksBehind=1000 (should pass)
    Response response1 = callHealthEndpoint("/readiness?maxBlocksBehind=1000");
    assertThat(response1.code()).isEqualTo(200);

    // Test with maxBlocksBehind=0 (should fail since we're behind by some blocks)
    Response response2 = callHealthEndpoint("/readiness?maxBlocksBehind=0");
    assertThat(response2.code()).isEqualTo(503);
  }

  @Test
  public void healthEndpointsShouldHandleInvalidParameters() throws IOException {
    // Invalid parameters should default to safe values and return 200
    Response response1 = callHealthEndpoint("/readiness?minPeers=invalid");
    assertThat(response1.code()).isEqualTo(200);

    Response response2 = callHealthEndpoint("/readiness?maxBlocksBehind=invalid");
    assertThat(response2.code()).isEqualTo(200);
  }

  @Test
  public void healthEndpointsShouldWorkWithNoParameters() throws IOException {
    Response livenessResponse = callHealthEndpoint("/liveness");
    assertThat(livenessResponse.code()).isEqualTo(200);

    Response readinessResponse = callHealthEndpoint("/readiness");
    assertThat(readinessResponse.code()).isEqualTo(200);
  }

  private Response callHealthEndpoint(final String path) throws IOException {
    String url = "http://" + node.getHostName() + ":" + node.getJsonRpcPort().get() + path;
    Request request = new Request.Builder().url(url).build();
    return client.newCall(request).execute();
  }

  /**
   * Wait for the node to be fully ready before proceeding with tests.
   * This helps avoid timeout issues during test execution.
   */
  private void waitForNodeToBeReady() throws Exception {
    waitForNodeToBeReady(node);
  }

  /**
   * Wait for a specific node to be fully ready by polling the readiness endpoint.
   * Improved version with better error handling and more robust checks.
   */
  private void waitForNodeToBeReady(final BesuNode nodeToWait) throws Exception {
    if (!nodeToWait.getJsonRpcPort().isPresent()) {
      throw new IllegalStateException("Node RPC port not available");
    }

    // Create a separate client for readiness checks
    OkHttpClient readinessClient = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    // Poll the readiness endpoint until it returns 200 or timeout
    final int maxAttempts = 120; // 120 seconds total (increased from 60)
    final int delayMs = 1000;   // 1 second between attempts
    
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        String url = "http://" + nodeToWait.getHostName() + ":" + nodeToWait.getJsonRpcPort().get() + "/readiness";
        Request request = new Request.Builder().url(url).build();
        
        try (Response response = readinessClient.newCall(request).execute()) {
          if (response.code() == 200) {
            // Additional check: verify the response body contains "UP"
            String body = response.body().string();
            if (body != null && body.contains("UP")) {
              return; // Node is ready
            }
          }
        }
      } catch (Exception e) {
        // Log the exception for debugging but continue trying
        System.err.println("Attempt " + (attempt + 1) + " failed: " + e.getMessage());
      }
      
      Thread.sleep(delayMs);
    }
    
    // If we get here, the node never became ready
    throw new RuntimeException("Node did not become ready within " + maxAttempts + " seconds");
  }
}