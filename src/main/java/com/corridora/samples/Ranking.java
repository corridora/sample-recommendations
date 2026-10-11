package com.corridora.samples;

import jakarta.inject.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Singleton
public class Ranking {
  @Inject Telemetry telemetry;
  @Inject ObjectMapper json;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).followRedirects(HttpClient.Redirect.NEVER).build();
  public static List<Map<String,Object>> score(double popularity, double quality, double rating) {
    var rows=new ArrayList<Map<String,Object>>();
    String[] ids={"demo-product","cloud-camera","trace-headphones"};double[] pops={.9,.7,.6}, qualities={.8,.95,.75};
    for(int i=0;i<ids.length;i++) rows.add(Map.of("id",ids[i],"score",Math.round((pops[i]*popularity+qualities[i]*quality+rating/5*.2)/(popularity+quality+.2)*1000d)/1000d));
    rows.sort((a,b)->Double.compare((double)b.get("score"),(double)a.get("score")));return rows;
  }
  public Map<String,Object> run(String product, double popularity, double quality, Scenario.State state) throws Exception {
    if(!Double.isFinite(popularity) || !Double.isFinite(quality) || popularity<.1 || quality<.1 || popularity>3 || quality>3) throw new IllegalArgumentException("Ranking weights must be between 0.1 and 3");
    Span.current().setAttribute("demo.scenario","ranking-model-change").setAttribute("demo.phase",state.phase()).setAttribute("demo.release",state.release());
    var span=telemetry.tracer.spanBuilder("ranking.compute").setAttribute("demo.scenario","ranking-model-change").setAttribute("demo.phase",state.phase()).setAttribute("demo.release",state.release()).setAttribute("model.revision",state.model_revision()).startSpan();
    try(var scope=span.makeCurrent()) {
      if(state.phase().equals("regression") && Set.of("demo-product","demo-invalid").contains(product)) {
        var extra=telemetry.tracer.spanBuilder("ranking.extra_pass.simulated").setAttribute("demo.simulated",true).setAttribute("demo.scenario","ranking-model-change").setAttribute("demo.phase",state.phase()).startSpan();
        try(var s=extra.makeCurrent()) {Thread.sleep(1500);}finally{extra.end();}
        if(product.equals("demo-invalid")) {span.setStatus(StatusCode.ERROR,"Seeded model schema validation failed");throw new IllegalStateException("Simulated model revision rejects demo-invalid");}
      }
      double rating=4.5;boolean fallback=false;String source="local-fixture";
      String endpoint=Settings.env("REVIEW_SUMMARY_URL","");
      if(!endpoint.isBlank()) {
        var uri=Settings.controlled(endpoint);
        if(!uri.getPath().equals("/internal/review-summary")) throw new IllegalArgumentException("REVIEW_SUMMARY_URL must use the AWS leaf /internal/review-summary");
        var downstream=telemetry.tracer.spanBuilder("GET review-summary").setSpanKind(SpanKind.CLIENT).setAttribute("server.address",uri.getHost()).startSpan();
        try(var s=downstream.makeCurrent()) {
          var b=HttpRequest.newBuilder(URI.create(endpoint+"?product_id=demo-product")).timeout(Duration.ofMillis(1000)).GET();
          telemetry.inject(Context.current()).forEach(b::header);
          String token=Settings.env("DOWNSTREAM_AUTH_TOKEN","");if(!token.isBlank()) b.header("Authorization","Bearer "+token);
          var response=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
          downstream.setAttribute("http.response.status_code",response.statusCode());
          if(response.statusCode()!=200 || response.body().length()>16384) throw new IllegalStateException("Review service unavailable");
          double candidate=json.readTree(response.body()).path("rating").asDouble(-1);
          if(!Double.isFinite(candidate)||candidate<0||candidate>5) throw new IllegalStateException("Invalid review summary");
          rating=candidate;source="aws-review-summary";
        }catch(Exception e){downstream.setStatus(StatusCode.ERROR);fallback=true;source="bounded-fallback";telemetry.log("Review summary unavailable; using fixture rating","dependency.fallback",state);}finally{downstream.end();}
      }
      var items=score(popularity,quality,rating);
      telemetry.count("recommendations.requests",1,Attributes.builder().put("demo.scenario","ranking-model-change").put("demo.phase",state.phase()).put("fallback",fallback).build());
      telemetry.log("Ranked "+items.size()+" fixture products","ranking.completed",state);
      return Map.of("items",items,"model_revision",state.model_revision(),"review_source",source,"fallback",fallback,"inputs",Map.of("popularity",popularity,"quality",quality),"demo_release",state.release());
    }catch(Exception e){span.setStatus(StatusCode.ERROR);throw e;}finally{span.end();}
  }
}
