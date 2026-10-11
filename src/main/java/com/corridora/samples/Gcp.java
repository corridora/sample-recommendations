package com.corridora.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.TokenVerifier;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Small explicit REST adapters: ADC comes from the Cloud Run metadata identity. */
@Singleton
public class Gcp {
  @Inject Telemetry telemetry;
  @Inject ObjectMapper json;
  final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).followRedirects(HttpClient.Redirect.NEVER).build();
  static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
  String accessToken() throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"))
      .header("Metadata-Flavor","Google").timeout(Duration.ofMillis(700)).GET().build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200) throw new IllegalStateException("Metadata identity unavailable");
    return json.readTree(response.body()).get("access_token").asText();
  }
  String request(String method, String url, String body) throws Exception {
    var span=telemetry.tracer.spanBuilder("gcp."+method.toLowerCase()).setSpanKind(SpanKind.CLIENT).setAttribute("server.address",URI.create(url).getHost()).startSpan();
    try(var scope=span.makeCurrent()) {
      var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(1500)).header("Authorization","Bearer "+accessToken());
      if(body!=null) b.header("Content-Type","application/json");
      // Google API destinations are platform calls; keep propagation within controlled app dependencies.
      b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
      var r=client.send(b.build(),HttpResponse.BodyHandlers.ofString()); span.setAttribute("http.response.status_code",r.statusCode());
      if(r.statusCode()==404 && method.equals("GET")) return null;
      if(r.statusCode()>=300) throw new IllegalStateException("Google API "+method+" returned "+r.statusCode());
      return r.body();
    } catch(Exception e) {span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);throw e;} finally {span.end();}
  }
  public String read(String object) throws Exception {
    return request("GET","https://storage.googleapis.com/storage/v1/b/"+encode(Settings.env("GCP_BUCKET",""))+"/o/"+encode(objectPath(object))+"?alt=media",null);
  }
  public void write(String object, String value) throws Exception {
    request("POST","https://storage.googleapis.com/upload/storage/v1/b/"+encode(Settings.env("GCP_BUCKET",""))+"/o?uploadType=media&name="+encode(objectPath(object)),value);
  }
  public static String objectPath(String object) {
    if(!object.startsWith("recommendations/") || object.contains(".."))throw new IllegalArgumentException("Invalid sample object name");
    return Settings.env("GCP_OBJECT_PREFIX","recommendations/"+Settings.env("GCP_PREFIX","sample-recs")+"/")+object.substring("recommendations/".length());
  }
  public String publish(Map<String,Object> body) throws Exception {
    var span=telemetry.tracer.spanBuilder("model.refresh publish").setSpanKind(SpanKind.PRODUCER).setAttribute("messaging.system","gcp_pubsub").startSpan();
    try(var scope=span.makeCurrent()) {
      var message=Map.of("data",Base64.getEncoder().encodeToString(json.writeValueAsBytes(body)),"attributes",telemetry.inject(Context.current()));
      return request("POST","https://pubsub.googleapis.com/v1/projects/"+Settings.env("GCP_PROJECT","")+"/topics/"+Settings.env("GCP_REFRESH_TOPIC","")+":publish",json.writeValueAsString(Map.of("messages",List.of(message))));
    } finally { span.end(); }
  }
  public boolean authenticatedPush(String authorization) {
    if(!Settings.cloud()) return Api.authorized(authorization, Settings.env("DEMO_CONTROL_TOKEN",""));
    if(authorization==null || !authorization.startsWith("Bearer ")) return false;
    try {
      var verified=TokenVerifier.newBuilder().setAudience(Settings.env("PUBLIC_URL","")).setIssuer("https://accounts.google.com").build().verify(authorization.substring(7));
      return Settings.env("GCP_PUSH_IDENTITY","").equals(verified.getPayload().get("email")) && Boolean.TRUE.equals(verified.getPayload().get("email_verified"));
    } catch(Exception e) { return false; }
  }
  public void monitoring() throws Exception {
    String project=Settings.env("GCP_PROJECT","");
    String filter="metric.type=\"run.googleapis.com/request_count\" AND resource.labels.service_name=\""+Settings.env("GCP_SERVICE","sample-recommendations")+"\"";
    String url="https://monitoring.googleapis.com/v3/projects/"+project+"/timeSeries?filter="+encode(filter)+"&interval.endTime="+encode(java.time.Instant.now().toString())+"&interval.startTime="+encode(java.time.Instant.now().minusSeconds(900).toString())+"&pageSize=3";
    var raw=request("GET",url,null);
    if(raw!=null) for(var series : json.readTree(raw).path("timeSeries")) {
      if(series.path("points").isEmpty()) continue;
      var point=series.path("points").get(0).path("value");
      double value=point.has("doubleValue")?point.path("doubleValue").asDouble():point.path("int64Value").asDouble();
      telemetry.gauge("gcp.cloud_run.request_count",value,monitoringAttributes(series,project));
    }
  }
  public static io.opentelemetry.api.common.Attributes monitoringAttributes(com.fasterxml.jackson.databind.JsonNode series,String project) {
    var resource=series.path("resource").path("labels");var metric=series.path("metric").path("labels");
    return io.opentelemetry.api.common.Attributes.builder().put("cloud.account.id",project)
      .put("cloud.region",resource.path("location").asText())
      .put("gcp.cloud_run.service",resource.path("service_name").asText())
      .put("gcp.cloud_run.revision",resource.path("revision_name").asText())
      .put("http.response.status_code",metric.path("response_code").asText())
      .put("http.response.status_class",metric.path("response_code_class").asText()).build();
  }
}
