package com.corridora.samples;

import java.net.URI;

public final class Settings {
  private static final java.util.Map<String,String> secrets = loadSecrets();
  @SuppressWarnings("unchecked") private static java.util.Map<String,String> loadSecrets() {
    String path=System.getenv("SAMPLE_SECRET_FILE");if(path==null)return java.util.Map.of();
    try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(java.nio.file.Files.readString(java.nio.file.Path.of(path)),java.util.Map.class);}
    catch(Exception e){throw new IllegalStateException("Cannot read mounted sample configuration",e);}
  }
  private Settings() {}
  public static String env(String key, String fallback) { return System.getenv().getOrDefault(key, secrets.getOrDefault(key, fallback)); }
  public static boolean cloud() { return env("SAMPLE_MODE", "local").equals("cloud"); }
  public static String version() { return env("SERVICE_VERSION", "local-unversioned"); }
  public static String origin(String input) { var u = URI.create(input); return u.getScheme()+"://"+u.getAuthority(); }
  public static URI controlled(String value) {
    var u = URI.create(value);
    if (u.getUserInfo()!=null || u.getHost()==null || u.getFragment()!=null || u.getRawQuery()!=null)
      throw new IllegalArgumentException("Endpoint must have host and no userinfo, fragment or query");
    if (!u.getScheme().equals("https") && !(u.getScheme().equals("http") && (u.getHost().equals("localhost") || u.getHost().equals("127.0.0.1"))))
      throw new IllegalArgumentException("Only HTTPS or localhost HTTP endpoints allowed");
    return u;
  }
}
