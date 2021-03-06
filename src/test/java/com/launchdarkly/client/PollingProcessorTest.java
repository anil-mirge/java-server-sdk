package com.launchdarkly.client;

import com.launchdarkly.client.integrations.PollingDataSourceBuilder;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class PollingProcessorTest {
  private static final String SDK_KEY = "sdk-key";
  private static final long LENGTHY_INTERVAL = 60000;
  
  @Test
  public void builderHasDefaultConfiguration() throws Exception {
    UpdateProcessorFactory f = Components.pollingDataSource();
    try (PollingProcessor pp = (PollingProcessor)f.createUpdateProcessor(SDK_KEY, LDConfig.DEFAULT, null)) {
      assertThat(((DefaultFeatureRequestor)pp.requestor).baseUri, equalTo(LDConfig.DEFAULT_BASE_URI));
      assertThat(pp.pollIntervalMillis, equalTo(PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS));
    }
  }

  @Test
  public void builderCanSpecifyConfiguration() throws Exception {
    URI uri = URI.create("http://fake");
    UpdateProcessorFactory f = Components.pollingDataSource()
        .baseURI(uri)
        .pollIntervalMillis(LENGTHY_INTERVAL);
    try (PollingProcessor pp = (PollingProcessor)f.createUpdateProcessor(SDK_KEY, LDConfig.DEFAULT, null)) {
      assertThat(((DefaultFeatureRequestor)pp.requestor).baseUri, equalTo(uri));
      assertThat(pp.pollIntervalMillis, equalTo(LENGTHY_INTERVAL));
    }
  }
  
  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedConfigurationIsUsedWhenBuilderIsNotUsed() throws Exception {
    URI uri = URI.create("http://fake");
    LDConfig config = new LDConfig.Builder()
        .baseURI(uri)
        .pollingIntervalMillis(LENGTHY_INTERVAL)
        .stream(false)
        .build();
    UpdateProcessorFactory f = Components.defaultUpdateProcessor();
    try (PollingProcessor pp = (PollingProcessor)f.createUpdateProcessor(SDK_KEY, config, null)) {
      assertThat(((DefaultFeatureRequestor)pp.requestor).baseUri, equalTo(uri));
      assertThat(pp.pollIntervalMillis, equalTo(LENGTHY_INTERVAL));
    }
  }
  
  @Test
  @SuppressWarnings("deprecation")
  public void deprecatedConfigurationHasSameDefaultsAsBuilder() throws Exception {
    UpdateProcessorFactory f0 = Components.pollingDataSource();
    UpdateProcessorFactory f1 = Components.defaultUpdateProcessor();
    LDConfig config = new LDConfig.Builder().stream(false).build();
    try (PollingProcessor pp0 = (PollingProcessor)f0.createUpdateProcessor(SDK_KEY, LDConfig.DEFAULT, null)) {
      try (PollingProcessor pp1 = (PollingProcessor)f1.createUpdateProcessor(SDK_KEY, config, null)) {
        assertThat(((DefaultFeatureRequestor)pp1.requestor).baseUri,
            equalTo(((DefaultFeatureRequestor)pp0.requestor).baseUri));
        assertThat(pp1.pollIntervalMillis, equalTo(pp0.pollIntervalMillis));
      }
    }
  }
  
  @Test
  public void testConnectionOk() throws Exception {
    MockFeatureRequestor requestor = new MockFeatureRequestor();
    requestor.allData = new FeatureRequestor.AllData(new HashMap<String, FeatureFlag>(), new HashMap<String, Segment>());
    FeatureStore store = new InMemoryFeatureStore();
    
    try (PollingProcessor pollingProcessor = new PollingProcessor(requestor, store, LENGTHY_INTERVAL)) {    
      Future<Void> initFuture = pollingProcessor.start();
      initFuture.get(1000, TimeUnit.MILLISECONDS);
      assertTrue(pollingProcessor.initialized());
      assertTrue(store.initialized());
    }
  }

  @Test
  public void testConnectionProblem() throws Exception {
    MockFeatureRequestor requestor = new MockFeatureRequestor();
    requestor.ioException = new IOException("This exception is part of a test and yes you should be seeing it.");
    FeatureStore store = new InMemoryFeatureStore();

    try (PollingProcessor pollingProcessor = new PollingProcessor(requestor, store, LENGTHY_INTERVAL)) {
      Future<Void> initFuture = pollingProcessor.start();
      try {
        initFuture.get(200L, TimeUnit.MILLISECONDS);
        fail("Expected Timeout, instead initFuture.get() returned.");
      } catch (TimeoutException ignored) {
      }
      assertFalse(initFuture.isDone());
      assertFalse(pollingProcessor.initialized());
      assertFalse(store.initialized());
    }
  }

  @Test
  public void http400ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(400);
  }
  
  @Test
  public void http401ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(401);
  }

  @Test
  public void http403ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(403);
  }

  @Test
  public void http408ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(408);
  }

  @Test
  public void http429ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(429);
  }

  @Test
  public void http500ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(500);
  }
  
  private void testUnrecoverableHttpError(int status) throws Exception {
    MockFeatureRequestor requestor = new MockFeatureRequestor();
    requestor.httpException = new HttpErrorException(status);
    try (PollingProcessor pollingProcessor = new PollingProcessor(requestor, new InMemoryFeatureStore(), LENGTHY_INTERVAL)) {  
      long startTime = System.currentTimeMillis();
      Future<Void> initFuture = pollingProcessor.start();
      try {
        initFuture.get(10, TimeUnit.SECONDS);
      } catch (TimeoutException ignored) {
        fail("Should not have timed out");
      }
      assertTrue((System.currentTimeMillis() - startTime) < 9000);
      assertTrue(initFuture.isDone());
      assertFalse(pollingProcessor.initialized());
    }
  }
  
  private void testRecoverableHttpError(int status) throws Exception {
    MockFeatureRequestor requestor = new MockFeatureRequestor();
    requestor.httpException = new HttpErrorException(status);
    try (PollingProcessor pollingProcessor = new PollingProcessor(requestor, new InMemoryFeatureStore(), LENGTHY_INTERVAL)) {
      Future<Void> initFuture = pollingProcessor.start();
      try {
        initFuture.get(200, TimeUnit.MILLISECONDS);
        fail("expected timeout");
      } catch (TimeoutException ignored) {
      }
      assertFalse(initFuture.isDone());
      assertFalse(pollingProcessor.initialized());
    }
  }
  
  private static class MockFeatureRequestor implements FeatureRequestor {
    AllData allData;
    HttpErrorException httpException;
    IOException ioException;
    
    public void close() throws IOException {}

    public FeatureFlag getFlag(String featureKey) throws IOException, HttpErrorException {
      return null;
    }

    public Segment getSegment(String segmentKey) throws IOException, HttpErrorException {
      return null;
    }

    public AllData getAllData() throws IOException, HttpErrorException {
      if (httpException != null) {
        throw httpException;
      }
      if (ioException != null) {
        throw ioException;
      }
      return allData;
    }
  }
}
