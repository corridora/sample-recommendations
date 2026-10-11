package com.corridora.samples;

import jakarta.inject.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Singleton
public class Scenario {
  @Inject Gcp gcp;
  @Inject ObjectMapper json;
  @Inject Telemetry telemetry;
  public record State(String phase, String release, long expires_at, String model_revision) {}
  private String observed="";
  private State local;
  public static State effective(State override, Instant now, String dailyStart, int seconds) {
    if(override!=null && override.expires_at()>now.getEpochSecond()) return override;
    var start=now.atZone(ZoneOffset.UTC).toLocalDate().atTime(LocalTime.parse(dailyStart)).toInstant(ZoneOffset.UTC);
    if(now.isBefore(start))start=start.minusSeconds(86400); // A late UTC window may cross midnight.
    boolean bad=!now.isBefore(start) && now.isBefore(start.plusSeconds(seconds));
    return new State(bad?"regression":"healthy", "daily-"+start.toString()+ (bad?"-bad":"-good"), bad?start.plusSeconds(seconds).getEpochSecond():0, bad?"fixture-bad-v2":Settings.env("MODEL_REVISION","fixture-v1"));
  }
  public synchronized State current() {
    try {
      if(Settings.cloud()) {var data=gcp.read("recommendations/control.json");local=data==null?null:json.readValue(data,State.class);}
      else if(local==null && Files.exists(Path.of(".local/control.json"))) local=json.readValue(Files.readString(Path.of(".local/control.json")),State.class);
    } catch(Exception e) {throw new IllegalStateException("Control state could not be read; no unsafe stale override",e);}
    var state=effective(local,Instant.now(),Settings.env("DEMO_DAILY_START_UTC","15:00"),Integer.parseInt(Settings.env("DEMO_WINDOW_SECONDS","600")));
    if(state.phase().equals("healthy")) try {
      String model=Settings.cloud()?gcp.read("recommendations/model.json"):Files.exists(Path.of(".local/model.json"))?Files.readString(Path.of(".local/model.json")):null;
      if(model!=null)state=new State(state.phase(),state.release(),state.expires_at(),json.readTree(model).path("model_revision").asText(Settings.env("MODEL_REVISION","fixture-v1")));
    }catch(Exception e){telemetry.log("Model refresh unavailable; retaining deterministic fixture","model.fallback",state);}
    if(!observed.equals(state.release())) {observed=state.release();telemetry.log("Model configuration now "+state.phase(),"deployment",state);}
    return state;
  }
  public synchronized State change(String phase, int seconds) throws Exception {
    if(!Set.of("healthy","regression","recover").contains(phase) || seconds<1 || seconds>600) throw new IllegalArgumentException("phase must be healthy/regression/recover; duration_seconds must be 1..600");
    local=new State(phase.equals("regression")?"regression":"healthy","override-"+UUID.randomUUID(),Instant.now().plusSeconds(seconds).getEpochSecond(),phase.equals("regression")?"fixture-bad-v2":Settings.env("MODEL_REVISION","fixture-v1"));
    save("recommendations/control.json",json.writeValueAsString(local));
    telemetry.log("Simulated model rollout: "+phase,"feature_flag",local);observed=local.release(); return local;
  }
  public void save(String object,String value) throws Exception {
    if(Settings.cloud()) gcp.write(object,value);
    else {Files.createDirectories(Path.of(".local"));var target=Path.of(".local",object.substring(object.lastIndexOf('/')+1));var temp=Files.createTempFile(Path.of(".local"),"state", ".tmp");Files.writeString(temp,value);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  }
}
