package com.corridora.samples;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
  @Test void dailyAndOverrideExpiry() {
    var during=Instant.parse("2026-10-11T15:05:00Z");
    assertEquals("regression",Scenario.effective(null,during,"15:00",600).phase());
    assertEquals("healthy",Scenario.effective(null,Instant.parse("2026-10-11T15:10:00Z"),"15:00",600).phase());
    var expired=new Scenario.State("regression","expired",during.minusSeconds(1).getEpochSecond(),"bad");
    assertEquals("healthy",Scenario.effective(expired,Instant.parse("2026-10-11T16:00:00Z"),"15:00",600).phase());
    var recovery=new Scenario.State("healthy","override",during.plusSeconds(60).getEpochSecond(),"fixture-v1");
    assertEquals("healthy",Scenario.effective(recovery,during,"15:00",600).phase());
    assertEquals("regression",Scenario.effective(null,Instant.parse("2026-10-12T00:01:00Z"),"23:55",600).phase());
    assertEquals("healthy",Scenario.effective(null,Instant.parse("2026-10-12T00:05:00Z"),"23:55",600).phase());
  }
  @Test void controlsRejectEmptyAndWrongTokens() {
    assertFalse(Api.authorized("Bearer ",""));assertFalse(Api.authorized(null,"secret"));assertFalse(Api.authorized("Bearer other","secret"));assertTrue(Api.authorized("Bearer secret","secret"));
  }
  @Test void weightsChangeRealOrdering() {
    assertEquals("demo-product",Ranking.score(3,.1,4.5).getFirst().get("id"));
    assertEquals("cloud-camera",Ranking.score(.1,3,4.5).getFirst().get("id"));
    assertEquals(3,Ranking.score(1,1,4.5).size());
  }
  @Test void contextHasCorrectParentAndOutboundContinuation() {
    var telemetry=new Telemetry();var sink=InMemorySpanExporter.create();
    try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build()) {
      String trace="1234567890abcdef1234567890abcdef",parent="1234567890abcdef";
      var ctx=telemetry.extract(Map.of("traceparent","00-"+trace+"-"+parent+"-01"));
      var span=provider.get("test").spanBuilder("server").setSpanKind(SpanKind.SERVER).setParent(ctx).startSpan();
      assertEquals(trace,span.getSpanContext().getTraceId());
      var outbound=telemetry.inject(ctx.with(span));assertTrue(outbound.get("traceparent").contains(span.getSpanContext().getSpanId()));span.end();
      assertEquals(parent,sink.getFinishedSpanItems().getFirst().getParentSpanId());
    }finally{telemetry.stop();}
  }
  @Test void dependencyUrlAllowlist() {
    assertThrows(IllegalArgumentException.class,()->Settings.controlled("http://evil.example/path"));
    assertThrows(IllegalArgumentException.class,()->Settings.controlled("https://key:secret@example.com/path"));
    assertThrows(IllegalArgumentException.class,()->Settings.controlled("https://example.com/path?key=secret"));
    assertEquals("localhost",Settings.controlled("http://localhost:1234/internal/review-summary").getHost());
  }
  @Test void providerConversionDropsQueryAndPayload() throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var entry=json.readTree("{\"logName\":\"projects/test/logs/run.googleapis.com%2Frequests\",\"httpRequest\":{\"requestMethod\":\"GET\",\"status\":503,\"latency\":\"1.5s\",\"requestUrl\":\"https://example.invalid/?key=secret\"},\"textPayload\":\"private\"}");
    assertEquals("Cloud Run GET 503 latency=1.5s",Api.providerDescription(entry));
    assertNull(Api.providerDescription(json.readTree("{\"logName\":\"application\",\"textPayload\":\"secret\"}")));
  }
  @Test void nativeMetricsPreserveSeriesAndObjectsStayNamespaced() throws Exception {
    var json=new com.fasterxml.jackson.databind.ObjectMapper();
    var one=json.readTree("{\"resource\":{\"labels\":{\"service_name\":\"recs\",\"revision_name\":\"recs-1\",\"location\":\"us-central1\"}},\"metric\":{\"labels\":{\"response_code\":\"200\",\"response_code_class\":\"2xx\"}}}");
    var two=one.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)two.path("metric").path("labels")).put("response_code","500");
    assertNotEquals(Gcp.monitoringAttributes(one,"project"),Gcp.monitoringAttributes(two,"project"));
    assertEquals("recommendations/sample-recs/control.json",Gcp.objectPath("recommendations/control.json"));
    assertThrows(IllegalArgumentException.class,()->Gcp.objectPath("other/control.json"));
  }
}
