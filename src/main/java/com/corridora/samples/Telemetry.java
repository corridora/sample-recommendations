package com.corridora.samples;

import io.opentelemetry.api.*;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.*;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.logs.*;
import io.opentelemetry.sdk.logs.export.*;
import io.opentelemetry.sdk.metrics.*;
import io.opentelemetry.sdk.metrics.export.*;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
import java.util.Collection;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.concurrent.TimeUnit;

/** Explicit lifecycle: no exporter HTTP auto-instrumentation, no background-only flush. */
@Singleton
public class Telemetry {
  public final OpenTelemetrySdk sdk;
  public final Tracer tracer;
  private final SdkTracerProvider traces;
  private final SdkLoggerProvider logs;
  private final SdkMeterProvider metrics;
  private final PeriodicMetricReader reader;
  private volatile boolean exportHealthy = true;
  private final java.util.concurrent.atomic.AtomicLong exportFailures=new java.util.concurrent.atomic.AtomicLong();
  private final Map<String,Boolean> providerReceipts=java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
  private final String key = Settings.env("CORRIDORA_INGEST_KEY", "");
  public Telemetry() {
    var base = Settings.env("CORRIDORA_OTLP_ENDPOINT", "https://ingest.corridora.com").replaceAll("/+$", "");
    Settings.controlled(base);
    var resource = Resource.create(Attributes.builder()
      .put("service.name", Settings.env("OTEL_SERVICE_NAME", "sample-recommendations-api"))
      .put("service.namespace", Settings.env("OTEL_SERVICE_NAMESPACE", "corridora-samples.recommendations"))
      .put("service.version", Settings.version())
      .put("deployment.environment.name", Settings.env("DEPLOYMENT_ENVIRONMENT", "development"))
      .put("cloud.provider", Settings.cloud() ? "gcp" : "local")
      .put("cloud.platform", Settings.cloud() ? "gcp_cloud_run" : "local")
      .put("cloud.account.id", Settings.env("GCP_PROJECT", "local"))
      .put("cloud.region", Settings.env("GCP_REGION", "us-central1"))
      .put("service.instance.id", Settings.env("K_REVISION", "local")).build());
    var traceBuilder = SdkTracerProvider.builder().setResource(resource);
    var logBuilder = SdkLoggerProvider.builder().setResource(resource);
    var meterBuilder = SdkMeterProvider.builder().setResource(resource);
    if (!key.isBlank()) {
      var traceExporter = OtlpHttpSpanExporter.builder().setEndpoint(base+"/v1/traces").addHeader("X-CORRIDORA-KEY", key).setTimeout(Duration.ofMillis(900)).build();
      var logExporter = OtlpHttpLogRecordExporter.builder().setEndpoint(base+"/v1/logs").addHeader("X-CORRIDORA-KEY", key).setTimeout(Duration.ofMillis(900)).build();
      var metricExporter = OtlpHttpMetricExporter.builder().setEndpoint(base+"/v1/metrics").addHeader("X-CORRIDORA-KEY", key).setTimeout(Duration.ofMillis(900)).build();
      SpanExporter observedTrace=new SpanExporter() {
        public CompletableResultCode export(Collection<io.opentelemetry.sdk.trace.data.SpanData> data){return observe(traceExporter.export(data));}
        public CompletableResultCode flush(){return traceExporter.flush();}public CompletableResultCode shutdown(){return traceExporter.shutdown();}
      };
      LogRecordExporter observedLog=new LogRecordExporter() {
        public CompletableResultCode export(Collection<io.opentelemetry.sdk.logs.data.LogRecordData> data){
          var result=observe(logExporter.export(data));
          result.whenComplete(()->{synchronized(providerReceipts){for(var record:data)if("gcp.request".equals(record.getAttributes().get(AttributeKey.stringKey("event.name"))))providerReceipts.put(record.getSpanContext().getTraceId(),result.isSuccess());while(providerReceipts.size()>256)providerReceipts.remove(providerReceipts.keySet().iterator().next());}});
          return result;
        }
        public CompletableResultCode flush(){return logExporter.flush();}public CompletableResultCode shutdown(){return logExporter.shutdown();}
      };
      MetricExporter observedMetric=new MetricExporter() {
        public CompletableResultCode export(Collection<io.opentelemetry.sdk.metrics.data.MetricData> data){return observe(metricExporter.export(data));}
        public io.opentelemetry.sdk.metrics.data.AggregationTemporality getAggregationTemporality(io.opentelemetry.sdk.metrics.InstrumentType type){return metricExporter.getAggregationTemporality(type);}
        public CompletableResultCode flush(){return metricExporter.flush();}public CompletableResultCode shutdown(){return metricExporter.shutdown();}
      };
      traceBuilder.addSpanProcessor(BatchSpanProcessor.builder(observedTrace).setMaxQueueSize(256).setMaxExportBatchSize(64).setExporterTimeout(Duration.ofMillis(1000)).build());
      logBuilder.addLogRecordProcessor(BatchLogRecordProcessor.builder(observedLog).setMaxQueueSize(256).setMaxExportBatchSize(64).setExporterTimeout(Duration.ofMillis(1000)).build());
      reader = PeriodicMetricReader.builder(observedMetric).setInterval(Duration.ofSeconds(30)).build();
      meterBuilder.registerMetricReader(reader);
    } else reader = null;
    traces = traceBuilder.build(); logs = logBuilder.build(); metrics = meterBuilder.build();
    sdk = OpenTelemetrySdk.builder().setTracerProvider(traces).setLoggerProvider(logs).setMeterProvider(metrics)
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
    tracer = sdk.getTracer("corridora.samples.recommendations", "1.0.0");
  }
  public Context extract(Map<String,String> headers) {
    return sdk.getPropagators().getTextMapPropagator().extract(Context.root(), headers, new TextMapGetter<Map<String,String>>() {
      public Iterable<String> keys(Map<String,String> m) { return m.keySet(); }
      public String get(Map<String,String> m, String k) { return m.get(k); }
    });
  }
  public Map<String,String> inject(Context context) {
    var headers = new java.util.HashMap<String,String>();
    sdk.getPropagators().getTextMapPropagator().inject(context, headers, Map::put); return headers;
  }
  public void log(String message, String event, Scenario.State state) {
    var builder = sdk.getLogsBridge().get("corridora.samples.recommendations").logRecordBuilder()
      .setContext(Context.current()).setSeverity(Severity.INFO).setBody(message).setAttribute(AttributeKey.stringKey("event.name"), event);
    if (state!=null) builder.setAttribute(AttributeKey.booleanKey("demo.simulated"),true)
      .setAttribute(AttributeKey.stringKey("demo.scenario"),"ranking-model-change")
      .setAttribute(AttributeKey.stringKey("demo.phase"),state.phase())
      .setAttribute(AttributeKey.stringKey("demo.release"),state.release())
      .setAttribute(AttributeKey.stringKey("service.version"),Settings.version());
    builder.emit();
  }
  public void count(String name, long value, Attributes attrs) {
    sdk.getMeter("corridora.samples.recommendations").counterBuilder(name).build().add(value, attrs);
  }
  public void gauge(String name, double value, Attributes attrs) {
    sdk.getMeter("corridora.samples.recommendations").gaugeBuilder(name).build().set(value, attrs);
  }
  public boolean enabled() { return !key.isBlank(); }
  public boolean healthy() { return exportHealthy; }
  private CompletableResultCode observe(CompletableResultCode result) {
    result.whenComplete(()->{if(!result.isSuccess()){exportFailures.incrementAndGet();exportHealthy=false;}});return result;
  }
  public boolean providerDelivered(String traceId) {return Boolean.TRUE.equals(providerReceipts.remove(traceId));}
  public boolean flush() {
    if (!enabled()) return false;
    long failuresBefore=exportFailures.get();
    var a=traces.forceFlush(); var b=logs.forceFlush(); var c=metrics.forceFlush();
    long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1400);
    for (var result : java.util.List.of(a,b,c)) result.join(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    exportHealthy=a.isSuccess()&&b.isSuccess()&&c.isSuccess()&&exportFailures.get()==failuresBefore;
    if (!exportHealthy) System.err.println("Telemetry export did not finish successfully within 1400ms; inspect endpoint/key and provider networking");
    return exportHealthy;
  }
  @PreDestroy public void stop() { flush(); sdk.close(); }
}
