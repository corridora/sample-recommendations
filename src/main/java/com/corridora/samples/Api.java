package com.corridora.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import jakarta.inject.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Path("/")
@Singleton
public class Api {
  @Inject Telemetry telemetry;
  @Inject Scenario scenario;
  @Inject Ranking ranking;
  @Inject Gcp gcp;
  @Inject ObjectMapper json;
  @jakarta.ws.rs.core.Context HttpHeaders headers;
  public static boolean authorized(String value,String token) {
    return token!=null && !token.isBlank() && value!=null && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8));
  }
  void control() {if(!authorized(headers.getHeaderString("Authorization"),Settings.env("DEMO_CONTROL_TOKEN",""))) throw new NotAuthorizedException("Bearer");}
  void push() {if(!gcp.authenticatedPush(headers.getHeaderString("Authorization"))) throw new NotAuthorizedException("Bearer");}
  interface Operation {Object get() throws Exception;}
  Response request(String name, Operation op) {
    var map=new HashMap<String,String>(); headers.getRequestHeaders().forEach((key,value)->map.put(key.toLowerCase(Locale.ROOT),value.getFirst()));
    var span=telemetry.tracer.spanBuilder(name).setSpanKind(SpanKind.SERVER).setParent(telemetry.extract(map)).setAttribute("http.request.method",name.split(" ")[0]).setAttribute("http.route",name.split(" ")[1]).startSpan();
    Response response; boolean exported;
    try(var scope=span.makeCurrent()) {
      var result=op.get();response=Response.ok(result).build();span.setAttribute("http.response.status_code",200);
    } catch(WebApplicationException e) {span.setAttribute("http.response.status_code",e.getResponse().getStatus());response=Response.status(e.getResponse().getStatus()).entity(Map.of("error","Unauthorized or invalid request")).build();}
    catch(IllegalArgumentException e) {span.setAttribute("http.response.status_code",400);response=Response.status(400).entity(Map.of("error",e.getMessage())).build();}
    catch(Exception e) {span.setStatus(StatusCode.ERROR);span.setAttribute("http.response.status_code",500);try(var scope=span.makeCurrent()){telemetry.log("Request failed: "+e.getClass().getSimpleName(),"request.failed",null);telemetry.count("recommendations.failures",1,Attributes.empty());}boolean seeded=e.getMessage()!=null&&e.getMessage().startsWith("Simulated model revision");response=Response.serverError().entity(Map.of("error", seeded?"Seeded model validation failed":"Request failed; inspect the server trace", "simulated",seeded)).build();}
    finally{span.end();exported=telemetry.flush();}
    if(name.equals("POST /internal/provider-logs") && response.getStatus()<300 && (!exported || !telemetry.providerDelivered(span.getSpanContext().getTraceId())))
      response=Response.status(503).entity(Map.of("error","Provider forwarding export incomplete; retry this Pub/Sub message")).build();
    return Response.fromResponse(response).type(MediaType.APPLICATION_JSON).header("Cache-Control","no-store").header("X-Trace-Id",span.getSpanContext().getTraceId()).build();
  }
  @GET @Path("healthz") @Produces(MediaType.APPLICATION_JSON)
  public Map<String,Object> health() {return Map.of("status","ok","telemetry_enabled",telemetry.enabled(),"last_export_healthy",telemetry.healthy());}
  @GET @Path("api/trace-check")
  public Response traceCheck() {return request("GET /api/trace-check",()->Map.of("trace_id",Span.current().getSpanContext().getTraceId(),"span_id",Span.current().getSpanContext().getSpanId()));}
  @GET @Path("api/demo/status")
  public Response status() {return request("GET /api/demo/status",()->{var state=scenario.current();return Map.of("phase",state.phase(),"release",state.release(),"expires_at",state.expires_at(),"model_revision",state.model_revision(),"service_version",Settings.version(),"daily_start_utc",Settings.env("DEMO_DAILY_START_UTC","15:00"));});}
  @POST @Path("api/demo/scenario") @Consumes(MediaType.APPLICATION_JSON)
  public Response change(Map<String,Object> input) {return request("POST /api/demo/scenario",()->{control();Object duration=input.get("duration_seconds");if(!(duration instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue()) throw new IllegalArgumentException("duration_seconds must be a finite integer");return scenario.change(String.valueOf(input.get("phase")),n.intValue());});}
  @GET @Path("api/recommendations")
  public Response recommendations(@QueryParam("product_id") @DefaultValue("demo-product") String product,@QueryParam("popularity") @DefaultValue("1") double popularity,@QueryParam("quality") @DefaultValue("1") double quality) {
    return request("GET /api/recommendations",()->ranking.run(product,popularity,quality,scenario.current()));
  }
  @POST @Path("api/refresh")
  public Response refresh(@QueryParam("revision") @DefaultValue("fixture-v1") String revision) {return request("POST /api/refresh",()->{control();if(!revision.matches("[a-zA-Z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid revision");var body=Map.<String,Object>of("model_revision",revision);if(Settings.cloud()) return Map.of("published",true,"result",gcp.publish(body));scenario.save("recommendations/model.json",json.writeValueAsString(body));telemetry.log("Local model refreshed","model.refresh",scenario.current());return body;});}
  @POST @Path("internal/pubsub") @Consumes(MediaType.APPLICATION_JSON)
  public Response consume(Map<String,Object> envelope) {return request("POST /internal/pubsub",()->{
    push();var message=json.valueToTree(envelope).path("message");var attributes=new HashMap<String,String>();message.path("attributes").fields().forEachRemaining(e->attributes.put(e.getKey(),e.getValue().asText()));
    var consumer=telemetry.tracer.spanBuilder("model.refresh consume").setSpanKind(SpanKind.CONSUMER).setParent(telemetry.extract(attributes)).addLink(Span.current().getSpanContext()).setAttribute("messaging.system","gcp_pubsub").startSpan();
    try(var scope=consumer.makeCurrent()) {scenario.current();var data=json.readTree(Base64.getDecoder().decode(message.path("data").asText()));String revision=data.path("model_revision").asText();if(!revision.matches("[a-zA-Z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid revision");scenario.save("recommendations/model.json",data.toString());telemetry.log("Pub/Sub model configuration refreshed","model.refresh",null);return Map.of("refreshed",revision);}
    finally{consumer.end();}
  });}
  @POST @Path("internal/provider-logs") @Consumes(MediaType.APPLICATION_JSON)
  public Response providerLogs(Map<String,Object> envelope) {return request("POST /internal/provider-logs",()->{
    push();var message=json.valueToTree(envelope).path("message");var entry=json.readTree(Base64.getDecoder().decode(message.path("data").asText()));
    String description=providerDescription(entry);
    if(description!=null) {
      // Only whitelisted request metadata; never copy raw URL/query, headers or credentials.
      telemetry.log(description,"gcp.request",null);
      telemetry.count("gcp.forwarded_request_logs",1,Attributes.empty());
    }return Map.of("accepted",true);
  });}
  public static String providerDescription(com.fasterxml.jackson.databind.JsonNode entry) {
    if(!entry.path("logName").asText().contains("run.googleapis.com%2Frequests"))return null;
    var http=entry.path("httpRequest");return "Cloud Run "+http.path("requestMethod").asText()+" "+http.path("status").asInt()+" latency="+http.path("latency").asText();
  }
  @POST @Path("internal/tick")
  public Response tick() {return request("POST /internal/tick",()->{push();var state=scenario.current();if(state.phase().equals("regression")) ranking.run("demo-product",1,1,state);if(Settings.env("ENABLE_PROVIDER_FORWARDING","false").equals("true"))gcp.monitoring();return Map.of("phase",state.phase(),"browser_journey_required",true);});}
  @POST @Path("api/demo/heartbeat") @Consumes(MediaType.APPLICATION_JSON)
  public Response heartbeat(Map<String,Object> body) {return request("POST /api/demo/heartbeat",()->{control();var record=Map.of("utc_day",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(),"at",java.time.Instant.now().toString(),"success",Boolean.TRUE.equals(body.get("success")));scenario.save("recommendations/heartbeat.json",json.writeValueAsString(record));telemetry.count("demo.journey.heartbeat",1,Attributes.empty());return record;});}
  @GET @Path("api/demo/heartbeat")
  public Response heartbeatRead() {return request("GET /api/demo/heartbeat",()->{control();String value=Settings.cloud()?gcp.read("recommendations/heartbeat.json"):java.nio.file.Files.exists(java.nio.file.Path.of(".local/heartbeat.json"))?java.nio.file.Files.readString(java.nio.file.Path.of(".local/heartbeat.json")):"{}";return json.readTree(value==null?"{}":value);});}
  @GET @Path("rum-config.js") @Produces("application/javascript")
  public Response rumConfig() throws Exception {
    String rumKey=Settings.env("CORRIDORA_RUM_KEY","");
    String source=Settings.env("CORRIDORA_RUM_SCRIPT_URL","https://rum.corridora.com/corridora-rum.js");Settings.controlled(source);
    var config=new LinkedHashMap<String,String>();config.put("data-endpoint",Settings.env("CORRIDORA_OTLP_ENDPOINT","https://ingest.corridora.com"));config.put("data-service","sample-recommendations-web");config.put("data-service-namespace",Settings.env("OTEL_SERVICE_NAMESPACE","corridora-samples.recommendations"));config.put("data-env",Settings.env("DEPLOYMENT_ENVIRONMENT","development"));config.put("data-version",Settings.version());config.put("data-trace-sample-rate","1");config.put("data-long-tasks","true");
    // parser-inserted external script finishes before Vite's module bootstrap.
    String js="(()=>{const key="+safeJson(rumKey)+";if(!key)return;const u=new URL("+safeJson(source)+");u.searchParams.set('ckid',key);const a="+safeJson(config)+";a['data-trace-origins']=location.origin;const e=document.createElement('script');e.src=u.href;for(const [k,v]of Object.entries(a))e.setAttribute(k,v);document.write(e.outerHTML);})();";
    return Response.ok(js).header("Cache-Control","no-store").build();
  }
  String safeJson(Object o) throws Exception {return json.writeValueAsString(o).replace("<","\\u003c").replace(">","\\u003e");}
}
